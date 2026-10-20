#!/usr/bin/env python3
"""Export sanitized JUnit evidence without report properties, tokens, or captured request bodies."""
import hashlib
import json
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    suites=[]
    for pattern in ('backend/*/target/surefire-reports/TEST-*.xml','backend/*/target/failsafe-reports/TEST-*.xml'):
        for path in sorted(ROOT.glob(pattern)):
            report=ET.parse(path).getroot()
            suites.append({'name':report.attrib['name'],'tests':int(report.attrib['tests']),'failures':int(report.attrib['failures']),'errors':int(report.attrib['errors']),'skipped':int(report.attrib['skipped']),'cases':[case.attrib['name'] for case in report.findall('testcase')]})
    totals={name:sum(s[name] for s in suites) for name in ['tests','failures','errors','skipped']}
    if not suites or totals['failures'] or totals['errors']:raise SystemExit('Final reports are missing or failing')
    fingerprint=hashlib.sha256()
    for path in sorted([*ROOT.glob('backend/**/src/**/*.java'),*ROOT.glob('backend/**/src/**/*.sql')]):
        fingerprint.update(path.relative_to(ROOT).as_posix().encode());fingerprint.update(path.read_bytes())
    evidence={'capturedAt':datetime.now(timezone.utc).isoformat(),'backendTotals':totals,'sourceSha256':fingerprint.hexdigest(),'suites':suites,'reportSources':['backend/*/target/surefire-reports/TEST-*.xml','backend/*/target/failsafe-reports/TEST-*.xml'],'rawReportsRetainedInRepository':False,'cloudDeploymentPerformed':False}
    (ROOT/'docs/verification.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
    print('Exported sanitized evidence: '+json.dumps(totals))

if __name__=='__main__':main()
