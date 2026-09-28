#!/usr/bin/env python3
"""Prepare source-traceable initial data; does not write to the app database."""
import argparse
from collections import Counter
from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET

NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
MASTER_SHEETS = {'분전함마스터', '차단기마스터'}


def read_workbook(path):
    sheets = {}
    with zipfile.ZipFile(path) as archive:
        workbook = ET.fromstring(archive.read('xl/workbook.xml'))
        properties = workbook.find('m:workbookPr', NS)
        if properties is not None and properties.get('date1904') in ('1', 'true'):
            raise ValueError('This converter expects the source workbook’s 1900 date system')
        shared = [
            ''.join(t.text or '' for t in item.findall('.//m:t', NS))
            for item in ET.fromstring(archive.read('xl/sharedStrings.xml')).findall('m:si', NS)
        ]
        relations = {r.get('Id'): r.get('Target') for r in ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))}
        for sheet in workbook.find('m:sheets', NS):
            name = sheet.get('name')
            if name not in MASTER_SHEETS:
                continue
            target = relations[sheet.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')]
            root = ET.fromstring(archive.read(target.lstrip('/') if target.startswith('/') else 'xl/' + target))
            rows = {}
            for row in root.findall('m:sheetData/m:row', NS):
                cells = {}
                for cell in row:
                    if cell.find('m:f', NS) is not None:
                        raise ValueError(f'Formula requires review: {name}!{cell.get("r")}')
                    value = cell.find('m:v', NS)
                    text = value.text if value is not None else ''.join(t.text or '' for t in cell.findall('.//m:t', NS))
                    if cell.get('t') == 's' and text:
                        text = shared[int(text)]
                    if text:
                        cells[re.sub(r'\d', '', cell.get('r'))] = text
                if cells:
                    rows[int(row.get('r'))] = cells
            sheets[name] = {'rows': rows, 'merges': [m.get('ref') for m in root.findall('m:mergeCells/m:mergeCell', NS)]}
    return sheets


def normalized(value):
    return value.strip().upper()


def excel_date(value):
    if not value.strip():
        return None
    number = float(value)
    if not number.is_integer() or number <= 60:
        raise ValueError(f'Unexpected date: {value}')
    return (date(1899, 12, 30) + timedelta(days=int(number))).isoformat()


def prepare(path):
    sheets = read_workbook(path)
    panels = []
    for row, cells in sheets['분전함마스터']['rows'].items():
        if row < 3:
            continue
        original_building = int(cells['B'])
        panels.append({
            'sourceRow': row, 'building': {12: 2, 34: 3}.get(original_building, original_building),
            'floor': cells['C'].strip(), 'number': normalized(cells['D']),
            'location': normalized(cells.get('F', '')), 'note': cells.get('G', ''),
            'upstreamPanel': cells.get('E', ''),
        })
    breakers = []
    panel_number = ''
    for row, cells in sheets['차단기마스터']['rows'].items():
        if row < 3:
            continue
        if cells.get('B', '').strip():
            panel_number = normalized(cells['B'])
        if not cells.get('C', '').strip():
            continue
        number = normalized(cells['C'])
        port = normalized(cells.get('E', ''))
        if panel_number == 'A-LE-B2' and number in {f'E{i}' for i in range(1, 11)} and port == '20':
            port = '2P'
        if port and not re.fullmatch(r'[1-9]\d*P?', port):
            raise ValueError(f'Invalid PORT at row {row}: {port}')
        breakers.append({
            'sourceRow': row, 'panelNumber': panel_number, 'number': number, 'originalNumber': cells['C'],
            'kind': cells.get('D', '').strip(), 'ports': int(port.rstrip('P')) if port else None,
            'ratedAmps': normalized(cells.get('F', '')).removesuffix('A'),
            'installationDate': excel_date(cells.get('G', '')),
            'load': normalized(cells.get('H', '')), 'note': cells.get('I', ''),
        })
    totals = Counter((b['panelNumber'], b['number']) for b in breakers)
    seen = Counter()
    for breaker in breakers:
        key = breaker['panelNumber'], breaker['number']
        seen[key] += 1
        if totals[key] > 1:
            breaker['number'] += f' [{seen[key]}]'
    assert len({(b['panelNumber'], b['number']) for b in breakers}) == len(breakers)
    excluded_spares = [b for b in breakers if normalized(b['originalNumber']) in {'SPARE', 'SPARE(증설)'}]
    breakers = [b for b in breakers if b not in excluded_spares]
    merged = {}
    for panel in panels:
        key = panel['building'], panel['floor'], panel['number']
        if key not in merged:
            merged[key] = dict(panel, sourceRows=[panel['sourceRow']])
            continue
        previous = merged[key]
        previous['sourceRows'].append(panel['sourceRow'])
        for field in ('location', 'note', 'upstreamPanel'):
            if panel[field] and previous[field] and panel[field] != previous[field]:
                raise ValueError(f'Conflicting panel {key}: {field}')
            if panel[field]:
                previous[field] = panel[field]
    panels = list(merged.values())
    for panel in panels:
        if panel['upstreamPanel']:
            panel['note'] = '\n'.join(filter(None, [panel['note'], 'FROM분전함: ' + panel['upstreamPanel']]))
    if len({p['number'] for p in panels}) != len(panels):
        raise ValueError('Panel number alone cannot identify the parent master')
    if not {b['panelNumber'] for b in breakers} <= {p['number'] for p in panels}:
        raise ValueError('Breaker references an unknown panel')
    return {
        'formatVersion': 1,
        'sourceFile': path.name, 'sourceSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'datasetId': 'excel-masters-v1',
        'panels': panels, 'breakers': breakers, 'excludedSpareBreakers': excluded_spares,
        'sourceSheets': sheets,
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('workbook', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.workbook.resolve() == args.output.resolve():
        parser.error('Output must differ from the source workbook')
    data = prepare(args.workbook)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Prepared {len(data['panels'])} panels, {len(data['breakers'])} breakers; master sheets only. App DB unchanged.")
