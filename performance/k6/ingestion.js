import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Rate } from 'k6/metrics';

const profile = __ENV.PROFILE || 'sustained';
const api = __ENV.BASE_URL || 'http://localhost:8080';
const quotaRejections = new Rate('quota_rejections');
const unexpectedErrors = new Rate('unexpected_errors');
const rate = Number(__ENV.RATE || 5);
const duration = __ENV.DURATION || '30s';
const scenarios = {
  sustained: { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, preAllocatedVUs: 10, maxVUs: 100 },
  burst: { executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 200,
    stages: [{ target: rate, duration: '10s' }, { target: rate * 4, duration: '10s' }, { target: rate, duration: '10s' }] },
};
if (!scenarios[profile]) throw new Error('PROFILE must be sustained or burst');
if (!__ENV.API_KEY) throw new Error('API_KEY is required; run scripts/demo.py --prepare-load first');

export const options = {
  scenarios: { ingestion: scenarios[profile] },
  thresholds: {
    unexpected_errors: ['rate<0.01'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  const response = http.post(api + '/api/v1/events', JSON.stringify({
    eventType: __ENV.EVENT_TYPE || 'load.created', payload: { run: __ENV.RUN_ID || 'local', vu: exec.vu.idInTest, iteration: exec.scenario.iterationInTest,
      orderId: `load-${exec.scenario.iterationInTest}`, amount: 1499, currency: 'INR' },
  }), { headers: { 'Authorization': `Bearer ${__ENV.API_KEY}`, 'Content-Type': 'application/json',
    'Idempotency-Key': `${__ENV.RUN_ID || exec.scenario.startTime}-${exec.scenario.iterationInTest}` },
    timeout: '10s', tags: { name: 'POST /api/v1/events' } });
  quotaRejections.add(response.status === 429);
  unexpectedErrors.add(response.status !== 202 && response.status !== 429);
  check(response, { 'accepted or explicitly rate limited': r => r.status === 202 || r.status === 429 });
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH || '/results/summary.json']: JSON.stringify(data, null, 2) };
}
