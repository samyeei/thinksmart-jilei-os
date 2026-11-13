'use strict';

const native = 'jile.approvedSites';
const network = {urls: ['<all_urls>']};
const policy = (baidu) => [
  "default-src 'self'",
  "script-src 'self' blob: 'unsafe-inline' 'wasm-unsafe-eval'"
    + (baidu ? " https://psstatic.cdn.bcebos.com https://ms.bdstatic.com" : ""),
  "style-src 'self' 'unsafe-inline'" + (baidu ? " https://ms.bdstatic.com" : ""),
  "img-src 'self' data: blob:" + (baidu ? " https://ms.bdstatic.com" : ""),
  "font-src 'self' data:" + (baidu ? " https://ms.bdstatic.com" : ""),
  "media-src 'self' blob:",
  "worker-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'"
].join('; ');

browser.webRequest.onBeforeRequest.addListener(async ({url, type, documentUrl}) => {
  if (!/^https?:\/\//i.test(url)) return {cancel: true};
  try {
    const answer = await browser.runtime.sendNativeMessage(native, {type: 'check', url, resourceType: type, document: documentUrl || ''});
    return {cancel: answer !== true};
  } catch (_) {
    return {cancel: true};
  }
}, network, ['blocking']);

browser.webRequest.onHeadersReceived.addListener(({url, responseHeaders}) => ({
  responseHeaders: [
    ...(responseHeaders || []),
    {name: 'Content-Security-Policy', value: policy(/^https:\/\/(www|m)\.baidu\.com(?::443)?\//i.test(url))}
  ]
}), {urls: ['http://*/*', 'https://*/*'], types: ['main_frame', 'sub_frame']},
['blocking', 'responseHeaders']);

async function connect() {
  try {
    if (await browser.runtime.sendNativeMessage(native, {type: 'ready'}) === true) return;
  } catch (_) {
    // The app may still be attaching the native delegate.
  }
  setTimeout(connect, 300);
}
connect();
