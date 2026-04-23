import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
  },
};

export default function () {
  const url = __ENV.PROBE_URL || 'http://host.docker.internal:8081/actuator/health';
  const response = http.get(url, {
    timeout: __ENV.PROBE_TIMEOUT || '5s',
    tags: { name: 'docker_host_probe' },
  });

  check(response, {
    'probe status is 200': (res) => res.status === 200,
    'probe response contains UP': (res) => typeof res.body === 'string' && res.body.includes('"status":"UP"'),
  });
}
