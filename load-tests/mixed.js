import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const count = Number(__ENV.POOL_SIZE || 200);
  const codes = [];
  for (let i = 0; i < count; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ originalUrl: `https://example.com/mixed-pool/${i}`, customAlias: null }),
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
    shorten: {
      executor: 'constant-arrival-rate',
      exec: 'runShorten',
      rate: __ENV.SHORTEN_RPS ? Number(__ENV.SHORTEN_RPS) : 20,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 10,
      maxVUs: 30,
    },
    redirect: {
      executor: 'constant-arrival-rate',
      exec: 'runRedirect',
      rate: __ENV.REDIRECT_RPS ? Number(__ENV.REDIRECT_RPS) : 200,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 30,
      maxVUs: 50,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{scenario:shorten}': ['p(95)<200'],
    'http_req_duration{scenario:redirect}': ['p(95)<200'],
  },
};

export function runShorten() {
  const n = Math.random().toString(36).slice(2, 10);
  const res = http.post(
    `${BASE_URL}/api/v1/urls`,
    JSON.stringify({ originalUrl: `https://example.com/mixed/${n}`, customAlias: null }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  check(res, {
    'shorten returns 200': (r) => r.status === 200,
    'response has short url': (r) => JSON.parse(r.body || '{}').shortUrl !== undefined,
  });
}

export function runRedirect(codes) {
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