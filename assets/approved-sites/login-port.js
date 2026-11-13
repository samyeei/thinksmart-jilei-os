if (window.top === window) {
  const port = browser.runtime.connectNative('jile.siteLogin');
  port.onMessage.addListener(message => {
    if (message.clear === true) {
      window.__jileLoginFillEpoch = (window.__jileLoginFillEpoch || 0) + 1;
    } else {
      fillSavedLogin(message);
    }
  });
  port.postMessage('ready');
}
