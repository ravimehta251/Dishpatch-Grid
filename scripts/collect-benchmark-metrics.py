#!/usr/bin/env python3
"""Capture actual Prometheus samples for the recorded benchmark time windows."""
import json
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT=Path(__file__).resolve().parents[1]
QUERIES={
    'outboxPending':'max(dispatchgrid_outbox_pending)',
    'deliveriesPending':'max(dispatchgrid_delivery_pending)',
    'consumerLag':'max(kafka_consumer_fetch_manager_records_lag_max)',
    'workerCpu':'max(process_cpu_usage{application="dispatchgrid-worker"})',
    'apiCpu':'max(process_cpu_usage{application="dispatchgrid-api"})',
}

def main():
    count=0
    for path in sorted((ROOT/'performance/results').glob('*-environment.json')):
        env=json.loads(path.read_text(encoding='utf-8'))
        samples=env.get('resources',[])
        if not samples:continue
        start=samples[0]['time']
        end=path.stat().st_mtime
        data={'run':env['run'],'startEpochSeconds':start,'endEpochSeconds':end,'stepSeconds':5,'queries':{}}
        for name,query in QUERIES.items():
            url='http://localhost:9090/api/v1/query_range?'+urlencode({'query':query,'start':start,'end':end,'step':5})
            with urlopen(url,timeout=15) as response:
                data['queries'][name]=json.load(response)
        target=path.with_name(path.name.replace('-environment.json','-prometheus.json'))
        target.write_text(json.dumps(data,indent=2),encoding='utf-8')
        count+=1
    print('Captured Prometheus time windows for '+str(count)+' completed runs; empty series remain explicitly empty.')

if __name__=='__main__':main()
