import http from 'k6/http';
import { check } from 'k6';

// Stress scenario (Epic 5 story 5.5): ramping load up to 2x the nominal
// baseline rates (redirect 200 -> 400 rps, shorten 20 -> 40 rps) held for
// the final stage. Expected outcome: no 5xx; p95 may degrade during the peak
// stage — document it, do not "fix" thresholds to make it pass silently.

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const HOLD = __ENV.STRESS_HOLD || '4m'; // duration of the final 2x stage
const RAMP = __ENV.STRESS_RAMP || '2m'; // duration of each ramp stage

export function setup() {
  const count = Number(__ENV.POOL_SIZE || 200);
  const codes = [];
  for (let i = 0; i < count; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/urls`,
      JSON.stringify({ originalUrl: `https://example.com/stress-pool/${i}`, customAlias: null }),
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
    stress_redirect: {
      executor: 'ramping-arrival-rate',
      startRate: 100, // half of nominal
      timeUnit: '1s',
      preAllocatedVUs: 60,
      maxVUs: 200,
      stages: [
        { target: 200, duration: RAMP }, // nominal
        { target: 400, duration: RAMP }, // 2x nominal
        { target: 400, duration: HOLD }, // hold 2x
      ],
    },
    stress_shorten: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 10,
      maxVUs: 40,
      stages: [
        { target: 20, duration: RAMP },
        { target: 40, duration: RAMP },
        { target: 40, duration: HOLD },
      ],
    },
  },
  thresholds: {
    // Hard gate: no failed requests at any stage (5xx or connection errors)
    http_req_failed: ['rate<0.001'],
    // p95 gate kept at the SLO limit; a breach during 2x hold is EXPECTED to be
    // reviewed and documented, not silently relaxed
    'http_req_duration{scenario:stress_redirect}': ['p(95)<200'],
    'http_req_duration{scenario:stress_shorten}': ['p(95)<200'],
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
