/* Node test: verify AVNIVO streaming SHA-256 against native crypto across chunk boundaries. */
const fs=require('node:fs'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const source=fs.readFileSync(require('node:path').join(__dirname,'..','offline-model-installer.js'),'utf8');
const start=source.indexOf('class SHA256 {'),end=source.indexOf('async function ticket(id)');
assert(start>=0&&end>start,'SHA256 source missing');
const sha=vm.runInNewContext(source.slice(start,end)+'; SHA256');
for(const n of [0,1,3,55,56,63,64,65,127,128,129,4096,1048579]){
 const bytes=crypto.randomBytes(n),h=new sha();
 for(let i=0;i<bytes.length;i+=17)h.update(bytes.subarray(i,i+17));
 assert.equal(h.finish(),crypto.createHash('sha256').update(bytes).digest('hex'),'size '+n);
}
console.log('SHA256 streaming vectors passed (12 cases)');
