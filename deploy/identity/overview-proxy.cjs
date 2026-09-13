const http = require('node:http');
// Keep map documents and assets on the authenticated business origin.
module.exports = function overviewProxy(req, res) {
  if (!/^\/(overview-monitoring|overview)(\/|\?|$)/.test(req.url)) return false;
  const targetPath = req.url.replace(/^\/overview(?=\/|\?|$)/, '/overview-monitoring/overview');
  const headers = {...req.headers};
  delete headers.cookie;
  delete headers.authorization;
  for (const key of Object.keys(headers)) {
    if (key.startsWith('x-local-') || key === 'x-actor' || key === 'x-trusted-subject') delete headers[key];
  }
  const upstream = http.request({host:'127.0.0.1', port:63200, path:targetPath, method:req.method, headers}, response => {
    res.writeHead(response.statusCode, response.headers);
    response.pipe(res);
  });
  upstream.on('error', () => { res.writeHead(502, {'Content-Type':'text/plain; charset=utf-8'}); res.end('地图服务暂不可用，请稍后重试'); });
  req.pipe(upstream);
  return true;
};
