const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
function harness(){
 let forwarded;
 const sandbox={module:{exports:{}},require:()=>({request:(options)=>{forwarded=options;return {on(){}}}})};
 vm.runInNewContext(fs.readFileSync(__dirname+'/overview-proxy.cjs','utf8'),sandbox);
 return {run:sandbox.module.exports,forwarded:()=>forwarded};
}
test('map document and modules bypass business SPA fallback',()=>{
 for(const url of ['/overview-monitoring/?embed=1','/overview-monitoring/src/main.tsx','/overview-monitoring/@vite/client']){
  const h=harness();assert.equal(h.run({url,method:'GET',headers:{},pipe(){}},{}),true);
  assert.equal(h.forwarded().port,63200);assert.equal(h.forwarded().path,url);
 }
});
test('legacy map assets use the renderer base and do not receive credentials',()=>{
 const h=harness();h.run({url:'/overview/map.webp',method:'GET',headers:{cookie:'private',authorization:'private','x-trusted-subject':'admin'},pipe(){}},{});
 assert.equal(h.forwarded().path,'/overview-monitoring/overview/map.webp');
 assert.equal(Object.keys(h.forwarded().headers).length,0);
});
test('business and identity endpoints retain their existing routing',()=>{
 for(const url of ['/api/v1/session/me','/register.html','/overview-other']){
  const h=harness();assert.equal(h.run({url},{}),false);assert.equal(h.forwarded(),undefined);
 }
});
