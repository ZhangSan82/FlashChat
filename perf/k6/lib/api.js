import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

import { config } from './config.js';

export const businessFailures = new Counter('business_failures');

function withAuthHeaders(token, headers = {}, contentType = false) {
  const merged = { ...headers };
  if (contentType) {
    merged['Content-Type'] = 'application/json';
  }
  if (token) {
    merged[config.authHeader] = token;
  }
  return merged;
}

function buildRequestParams(token, requestOptions = {}, contentType = false) {
  return {
    ...requestOptions,
    headers: withAuthHeaders(token, requestOptions.headers, contentType),
  };
}

export function safeJson(response) {
  try {
    return response.json();
  } catch (error) {
    return null;
  }
}

function assertWrappedResult(response, tag) {
  const body = safeJson(response);
  const ok = check(response, {
    [`${tag} http status is 200`]: (res) => res.status === 200,
    [`${tag} business code is 200`]: () => body && body.code === '200',
  });

  if (!ok) {
    businessFailures.add(1, { tag });
    throw new Error(`${tag} failed, status=${response.status}, body=${response.body}`);
  }
  return body.data;
}

function assertPlainResult(response, tag, parser) {
  const parsed = parser(response);
  const ok = check(response, {
    [`${tag} status is 200`]: (res) => res.status === 200,
  });

  if (!ok) {
    businessFailures.add(1, { tag });
    throw new Error(`${tag} failed, status=${response.status}, body=${response.body}`);
  }
  return parsed;
}

export function postJson(path, payload, token, params = {}) {
  const url = `${config.baseUrl}${path}`;
  const body = payload === null || payload === undefined ? '' : JSON.stringify(payload);
  const { tag, ...requestOptions } = params;
  const requestParams = buildRequestParams(token, requestOptions, true);
  const response = http.post(url, body, requestParams);
  return assertWrappedResult(response, tag || path);
}

export function postWrappedJson(path, payload, token, params = {}) {
  const url = `${config.baseUrl}${path}`;
  const body = payload === null || payload === undefined ? '' : JSON.stringify(payload);
  const { ...requestOptions } = params;
  const requestParams = buildRequestParams(token, requestOptions, true);
  const response = http.post(url, body, requestParams);
  return { response, body: safeJson(response) };
}

export function getJson(path, token, params = {}) {
  const url = `${config.baseUrl}${path}`;
  const { tag, ...requestOptions } = params;
  const requestParams = buildRequestParams(token, requestOptions, false);
  const response = http.get(url, requestParams);
  return assertWrappedResult(response, tag || path);
}

export function getPlainJson(path, params = {}) {
  const url = `${config.baseUrl}${path}`;
  const { tag, ...requestOptions } = params;
  const response = http.get(url, requestOptions);
  return assertPlainResult(response, tag || path, safeJson);
}

export function getPlainText(path, params = {}) {
  const url = `${config.baseUrl}${path}`;
  const { tag, ...requestOptions } = params;
  const response = http.get(url, requestOptions);
  return assertPlainResult(response, tag || path, (res) => res.body);
}
