import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    platform: { executor: 'constant-vus', vus: 10, duration: '30s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const plans = http.get(`${BASE}/api/v1/plans`);
  check(plans, { 'plans 200': (r) => r.status === 200 });

  const health = http.get(`${BASE}/api/v1/health`);
  check(health, { 'health 200': (r) => r.status === 200 });

  sleep(0.5);
}
