import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const count = Number(__ENV.POOL_SIZE || 200);
  const codes = [];
  for (let i = 0; i < count; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ originalUrl: `https://example.com/redirect-pool/${i}`, customAlias: null }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status === 200) {
      const shortUrl = JSON.parse(res.body).shortUrl;
      codes.push(shortUrl.substring(shortUrl.lastIndexOf('/') + 1));
    }
  }
  return codes;
}

export const options = {
  scenarios: {
    redirect: {
      executor: 'constant-arrival-rate',
      rate: __ENV.REDIRECT_RPS ? Number(__ENV.REDIRECT_RPS) : 200,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 30,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{scenario:redirect}': ['p(95)<200'],
  },
};

export default function (codes) {
  if (!codes || codes.length === 0) {
    return;
  }
  const code = codes[Math.floor(Math.random() * codes.length)];
  const res = http.get(`${BASE_URL}/${code}`, { redirects: 0 });
  check(res, {
    'redirect is 302 with Location': (r) =>
      r.status === 302 && r.headers['Location'] !== undefined,
  });
}