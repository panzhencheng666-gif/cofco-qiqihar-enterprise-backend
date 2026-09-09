const https=require('https'),http=require('http'),fs=require('fs'),path=require('path');
const tls={key:fs.readFileSync(__dirname+'/localhost-key.pem'),cert:fs.readFileSync(__dirname+'/localhost.pem')};
const dist=__dirname+'/web-dist';
function proxy(req,res){const headers={...req.headers,'x-forwarded-proto':'https','x-forwarded-host':req.headers.host};for(const k of Object.keys(headers))if(k.startsWith('x-local-')||k==='x-actor'||k==='x-trusted-subject')delete headers[k];const p=http.request({host:'127.0.0.1',port:28090,path:req.url,method:req.method,headers},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res)});p.on('error',()=>{res.writeHead(502);res.end('业务服务暂不可用')});req.pipe(p)}
https.createServer(tls,(req,res)=>{
 if(req.url.split('?')[0]==='/activation-required.html'){res.writeHead(302,{'Location':'/register.html','Cache-Control':'no-store'});return res.end()}
 if(req.url.split('?')[0]==='/register.html'){res.setHeader('Content-Type','text/html; charset=utf-8');res.setHeader('Cache-Control','no-store');return res.end(fs.readFileSync(__dirname+'/register.html'))}
 if(req.url==='/local-identity-delivery'){res.writeHead(503,{'Content-Type':'application/json'});return res.end('{"error":"LOCAL_DELIVERY_NOT_CONFIGURED"}')}
 if(/^\/(api|oauth2|login|logout|actuator)(\/|\?|$)/.test(req.url))return proxy(req,res);
 let rel;try{rel=decodeURIComponent(req.url.split('?')[0])}catch{res.writeHead(400);return res.end()}
 let f=path.resolve(dist,'.'+rel);if(!f.startsWith(dist+'/'))f=dist+'/index.html';if(!fs.existsSync(f)||fs.statSync(f).isDirectory()){if(path.extname(rel)||!['GET','HEAD'].includes(req.method)){res.writeHead(404);return res.end('页面不存在')}f=dist+'/index.html';}
 res.setHeader('Content-Type',({'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png'})[path.extname(f)]||'application/octet-stream');fs.createReadStream(f).pipe(res);
}).listen(29443,'127.0.0.1',()=>console.log('HTTPS business entry ready on 29443'));
