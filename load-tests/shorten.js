import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    shorten: {
      executor: 'constant-arrival-rate',
      rate: __ENV.SHORTEN_RPS ? Number(__ENV.SHORTEN_RPS) : 20,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 10,
      maxVUs: 30,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{scenario:shorten}': ['p(95)<200'],
  },
};

function randomUrl() {
  const n = Math.random().toString(36).slice(2, 10);
  return `https://example.com/load/${n}?t=${Date.now()}`;
}

export default function () {
  const res = http.post(
    `${BASE_URL}/api/v1/urls`,
    JSON.stringify({ url: randomUrl(), customAlias: null }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, {
    'shorten returns 200': (r) => r.status === 200,
    'response has short url': (r) => JSON.parse(r.body || '{}').shortUrl !== undefined,
  });
}