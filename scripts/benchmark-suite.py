#!/usr/bin/env python3
"""Measured local scenarios. Uses the demo tenant and dev receiver, never prints credentials."""
import json
import os
import subprocess
import threading
import time
from demo import request, read_env, ROOT

def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, **kwargs)

def main():
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only',choices=['burst','fanout','slow','retry','one-worker','two-workers'],help='Run one scenario for a measured configuration comparison')
    args=parser.parse_args()
    secrets = json.loads((ROOT / '.local/demo-secrets.json').read_text())
    env = read_env()
    session = request('http://localhost:8080', 'POST', '/api/v1/auth/login', {'email': secrets['email'], 'password': secrets['password']})
    token = session['accessToken']
    tenant = request('http://localhost:8080', 'GET', '/api/v1/tenant', token=token)
    request('http://localhost:8080', 'PUT', '/api/v1/tenant', {'name':tenant['name'], 'rateLimitPerMinute':60000}, token)
    created = []
    signing = []
    receiver = 'http://localhost:8090'
    results = ROOT / 'performance/results'
    results.mkdir(exist_ok=True)
    batch = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())
    scenarios = [('burst', 'burst', 10, 1, 0, 0), ('fanout', 'sustained', 5, 3, 0, 0), ('slow', 'sustained', 5, 1, 500, 0), ('retry', 'sustained', 5, 1, 0, 100), ('one-worker', 'sustained', 20, 1, 0, 0), ('two-workers', 'sustained', 20, 1, 0, 0)]
    if args.only:scenarios=[s for s in scenarios if s[0]==args.only]
    try:
        # Dedicated event type keeps the original demo endpoint unchanged.
        for n in range(3):
            endpoint=request('http://localhost:8080','POST','/api/v1/endpoints',{'url':'http://receiver:8090/webhook','description':'Benchmark destination '+str(n+1),'active':True,'subscribedEvents':['benchmark.created'],'maxAttempts':5,'retryBaseSeconds':1,'retryMaxSeconds':2},token)
            created.append(endpoint); signing.append(endpoint['signingSecret'])
        for label,profile,rate,fanout,delay,failures in scenarios:
            workers=2 if label=='two-workers' else 1
            if workers==2:
                command(['docker','compose','up','-d','--no-recreate','--scale','worker=2','worker'],stdout=subprocess.DEVNULL)
            for n,endpoint in enumerate(created):
                config={k:endpoint[k] for k in ['url','description','subscribedEvents','maxAttempts','retryBaseSeconds','retryMaxSeconds']};config['active']=n<fanout
                request('http://localhost:8080','PUT','/api/v1/endpoints/'+endpoint['id'],config,token)
            request(receiver,'POST','/control',{'signingSecrets':signing,'delayMs':delay,'failureCount':failures,'status':500},env['RECEIVER_CONTROL_TOKEN'])
            run=batch+'-'+label
            stopped=threading.Event();samples=[]
            def sample():
                while not stopped.is_set():
                    output=command(['docker','stats','--no-stream','--format','{{json .}}'],capture_output=True).stdout
                    samples.append({'time':time.time(),'containers':[json.loads(line) for line in output.splitlines() if line]})
                    stopped.wait(3)
            thread=threading.Thread(target=sample,daemon=True);thread.start()
            kenv=os.environ.copy();kenv['API_KEY']=secrets['apiKey']
            try:
                with (results/(run+'-console.txt')).open('w') as log:
                    command(['docker','run','--rm','--network','dispatchgrid_default','-e','API_KEY','-e','BASE_URL=http://api:8080','-e','EVENT_TYPE=benchmark.created','-e','PROFILE='+profile,'-e','RATE='+str(rate),'-e','DURATION=20s','-e','RUN_ID='+run,'-e','SUMMARY_PATH=/results/'+run+'-summary.json','-v',str(ROOT/'performance/k6')+':/scripts:ro','-v',str(results)+':/results','grafana/k6:1.0.0','run','/scripts/ingestion.js','--out','json=/results/'+run+'-raw.json'],env=kenv,stdout=log,stderr=subprocess.STDOUT)
            finally:
                stopped.set();thread.join(10)
            # Wait for this workload to settle using authoritative DB state.
            predicate="e.payload->>'run'='"+run+"'"
            sql="SELECT count(*) FROM webhook_deliveries d JOIN events e ON e.id=d.event_id WHERE "+predicate+" AND d.status IN ('PENDING','PROCESSING','RETRY_SCHEDULED')"
            def sql_query(query):
                return command(['docker','compose','exec','-T','postgres','psql','-U',env.get('DB_USERNAME','dispatchgrid'),'-d','dispatchgrid','-Atc',query],capture_output=True).stdout.strip()
            events=int(sql_query("SELECT count(*) FROM events e WHERE "+predicate))
            expected=events*fanout
            count_sql="SELECT count(*) FROM webhook_deliveries d JOIN events e ON e.id=d.event_id WHERE "+predicate
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if int(sql_query(count_sql))==expected and int(sql_query(sql))==0:break
                time.sleep(1)
            summary_sql="""SELECT json_build_object('deliveries',count(*),'delivered',count(*) FILTER(WHERE d.status='DELIVERED'),'terminal',count(*) FILTER(WHERE d.status IN ('FAILED','EXHAUSTED')),'pending',count(*) FILTER(WHERE d.status IN ('PENDING','PROCESSING','RETRY_SCHEDULED')),'deliveryP50Ms',percentile_cont(.5) WITHIN GROUP(ORDER BY extract(epoch FROM(d.delivered_at-e.created_at))*1000),'deliveryP95Ms',percentile_cont(.95) WITHIN GROUP(ORDER BY extract(epoch FROM(d.delivered_at-e.created_at))*1000),'deliveryP99Ms',percentile_cont(.99) WITHIN GROUP(ORDER BY extract(epoch FROM(d.delivered_at-e.created_at))*1000),'attempts',sum(d.attempt_count)) FROM webhook_deliveries d JOIN events e ON e.id=d.event_id WHERE """+predicate
            output={'run':run,'profile':profile,'rate':rate,'workers':workers,'concurrencyPerWorker':8,'outboxPollMs':int(env.get('OUTBOX_POLL_MS','20')),'fanout':fanout,'receiverDelayMs':delay,'configuredFailures':failures,'events':events,'expectedDeliveries':expected,'delivery':json.loads(sql_query(summary_sql)),'resources':samples}
            (results/(run+'-environment.json')).write_text(json.dumps(output,indent=2))
            print('Measured '+label+': '+json.dumps(output['delivery']),flush=True)
    finally:
        request(receiver,'POST','/control',{'signingSecrets':[],'delayMs':0,'failureCount':0},env['RECEIVER_CONTROL_TOKEN'])
        for endpoint in created:request('http://localhost:8080','DELETE','/api/v1/endpoints/'+endpoint['id'],token=token,expected=(204,))
        request('http://localhost:8080','PUT','/api/v1/tenant',{'name':tenant['name'],'rateLimitPerMinute':tenant['rateLimitPerMinute']},token)
        command(['docker','compose','up','-d','--no-recreate','--scale','worker=1','worker'],stdout=subprocess.DEVNULL)

if __name__=='__main__':main()
