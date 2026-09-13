const fs=require('fs'),vm=require('vm'),assert=require('node:assert/strict');
const html=fs.readFileSync(require('path').join(__dirname,'../deploy/identity/pages/register.html'),'utf8');
const script=html.split('<script>')[1].split('</script>')[0];
async function run(statuses,query=''){
 const elements={},redirects=[],calls=[];
 const context={URLSearchParams,location:{search:query,replace:u=>redirects.push(u)},document:{getElementById:id=>elements[id]??=( {hidden:true,replaceChildren(){},options:[]} ),createElement:()=>({})},fetch:async url=>{calls.push(url);const status=statuses.shift();return {status,ok:status===200,json:async()=>({data:{workUnits:[],positions:[],regions:[{code:'1',name:'地区'}]}})}}};
 vm.runInNewContext(script,context);await new Promise(r=>setTimeout(r,10));return {elements,redirects,calls};
}
(async()=>{
 let r=await run([401,401]);assert.deepEqual(r.redirects,['/oauth2/authorization/enterprise']);
 r=await run([403,200]);assert.equal(r.elements.form.hidden,false);assert.equal(r.redirects.length,0);
 r=await run([200]);assert.deepEqual(r.redirects,['/']);assert.equal(r.calls.length,1);
 r=await run([500]);assert.equal(r.redirects.length,0);assert.equal(r.calls.length,1);
 r=await run([], '?reauthenticate=1');assert.deepEqual(r.redirects,['/oauth2/authorization/enterprise?reauthenticate=1']);assert.equal(r.calls.length,0);
 assert(!html.includes('第一步：创建登录账号'));console.log('5 navigation scenarios passed');
})().catch(e=>{console.error(e);process.exitCode=1});
