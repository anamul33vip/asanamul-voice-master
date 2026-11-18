// AS ANAMUL VOICE - Admin Panel + API (zero dependency, Node 18+)
// Run: ADMIN_PASS=yourpass node server.js
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto');
const PORT=process.env.PORT||3000, ADMIN_PASS=process.env.ADMIN_PASS||'';
if(ADMIN_PASS.length<12)throw new Error('Set ADMIN_PASS to a unique password of at least 12 characters before starting the server.');
// TTS credentials stay on the server; never place the provider API key in the Android app.
const ELEVENLABS_API_KEY=process.env.ELEVENLABS_API_KEY||'';
const ELEVENLABS_DEFAULT_VOICE_ID=process.env.ELEVENLABS_DEFAULT_VOICE_ID||'';
const ELEVENLABS_MODEL_ID=process.env.ELEVENLABS_MODEL_ID||'eleven_v3';
const TTS_MAX_CHARS=Math.max(100,Math.min(10000,Number(process.env.TTS_MAX_CHARS||3000)));
const TTS_CREDITS_PER_REQUEST=Math.max(0,Number(process.env.TTS_CREDITS_PER_REQUEST||0));
const ttsLastRequest=new Map();
const mintLast=new Map();
const DB_FILE=path.join(__dirname,'db.json'), UP=path.join(__dirname,'uploads');
fs.mkdirSync(UP,{recursive:true});
const defaults={keys:[],voices:[],coupons:[],voiceProfiles:[],config:{notice:'স্বাগতম!',tts_enabled:!!ELEVENLABS_API_KEY,tts_max_chars:TTS_MAX_CHARS,tts_voice_id:'',tts_model_id:ELEVENLABS_MODEL_ID,telegram:'https://t.me/crackffpanel',youtube:'https://www.youtube.com/@AnamulModzOfficial',buy_link:'https://t.me/crackffpanel',version:1,update_url:'',update_required:false,
app_version:'2.6.0',apk_url:'',release_note:'নতুন আপডেট সিস্টেম: Admin থেকে Version + APK URL + Release Note দিলেই ইউজার অ্যাপে Update দেখাবে। Voice Profiles যোগ হয়েছে।',active_voice_profile_id:''}};
let db=defaults;
if(fs.existsSync(DB_FILE))db=JSON.parse(fs.readFileSync(DB_FILE));
db.keys ||= []; db.voices ||= []; db.coupons ||= []; db.voiceProfiles ||= []; db.config={...defaults.config,...(db.config||{})};
// Compare dotted versions: cmpVer('2.6.0','2.5.0') > 0
const cmpVer=(a,b)=>{const pa=String(a||'0').split('.').map(x=>parseInt(x,10)||0),pb=String(b||'0').split('.').map(x=>parseInt(x,10)||0);for(let i=0;i<Math.max(pa.length,pb.length);i++){const d=(pa[i]||0)-(pb[i]||0);if(d)return d}return 0};
const updateInfo=clientVer=>({latest_version:db.config.app_version||'',apk_url:db.config.apk_url||db.config.update_url||'',release_note:db.config.release_note||'',required:!!db.config.update_required,update_available:!!(db.config.app_version&&clientVer&&cmpVer(db.config.app_version,clientVer)>0&&(db.config.apk_url||db.config.update_url))});
const activeProfile=()=>db.voiceProfiles.find(v=>v.id===db.config.active_voice_profile_id&&v.status==='ON')||db.voiceProfiles.find(v=>v.active&&v.status==='ON')||db.voiceProfiles.find(v=>v.status==='ON')||null;
const save=()=>fs.writeFileSync(DB_FILE,JSON.stringify(db,null,1));
const sessions={}; const now=()=>Math.floor(Date.now()/1000);
const rid=n=>crypto.randomBytes(n).toString('hex');
// Seed the first Voice Profile (Voice A — Jessica, the Free-plan default) so TTS works out of the box.
if(!db.voiceProfiles.length){
 const seedId=(db.config.tts_voice_id||ELEVENLABS_DEFAULT_VOICE_ID||'cgSgspJ2msm6clMCkdW9').trim();
 if(seedId){const p={id:rid(6),name:'Voice A',provider:'ElevenLabs',voice_id:seedId,language:'bn',status:'ON',active:true};db.voiceProfiles.push(p);db.config.active_voice_profile_id=p.id;db.config.tts_voice_id=seedId;save();}
}
const send=(r,c,o,t='application/json')=>{r.writeHead(c,{'Content-Type':t,'Access-Control-Allow-Origin':'*'});r.end(typeof o==='string'?o:JSON.stringify(o))};
const body=q=>new Promise(res=>{let b=[];q.on('data',d=>b.push(d));q.on('end',()=>{const s=Buffer.concat(b);try{res(JSON.parse(s.toString()))}catch{res({_raw:s})}})});
const isAdmin=q=>sessions[(q.headers.authorization||'').replace('Bearer ','')]==='admin';
function userFrom(q){const s=sessions[(q.headers.authorization||'').replace('Bearer ','')];return s&&s.key?db.keys.find(k=>k.key===s.key):null}
const base=q=>(process.env.PUBLIC_BASE_URL||(((q.headers['x-forwarded-proto']||'http').split(',')[0].trim())+'://'+q.headers.host)).replace(/\/$/,'');
http.createServer(async(q,r)=>{
 const u=new URL(q.url,'http://x'),p=u.pathname;
 if(q.method==='OPTIONS')return send(r,200,{});
 try{
 if(p==='/'||p==='/admin')return send(r,200,fs.readFileSync(path.join(__dirname,'admin.html'),'utf8'),'text/html; charset=utf-8');
 if(p==='/healthz')return send(r,200,{ok:true});
 if(p==='/getkey')return send(r,200,fs.readFileSync(path.join(__dirname,'getkey.html'),'utf8'),'text/html; charset=utf-8');
 if(p==='/api/web/mint'&&q.method==='POST'){
  const ip=(q.headers['x-forwarded-for']||q.socket.remoteAddress||'').split(',')[0].trim();
  const last=mintLast.get(ip)||0;
  if(Date.now()-last<12000)return send(r,429,{ok:false,error:'rate_limited'});
  mintLast.set(ip,Date.now());
  const b=await body(q),dev=String(b.device||'').trim().slice(0,100);
  if(!dev)return send(r,400,{ok:false,error:'device_required'});
  const ex=db.keys.find(x=>x.device===dev&&!x.banned&&x.expires&&x.expires>now());
  if(ex)return send(r,200,{ok:true,key:ex.key,expires_epoch:ex.expires,again:true});
  const k={key:'ANM-'+rid(4).toUpperCase(),name:'Web Free',credits:50,expires:now()+12*3600,device:dev,banned:false,created:now()};
  db.keys.push(k);save();
  return send(r,200,{ok:true,key:k.key,expires_epoch:k.expires,again:false})}
 if(p.startsWith('/files/')){const f=path.join(UP,path.basename(p));if(!fs.existsSync(f))return send(r,404,{});r.writeHead(200);return fs.createReadStream(f).pipe(r)}
 // ---------- UPDATE (public, no login needed) ----------
 // Admin Panel -> Version + APK URL + Release Note -> User App shows "Update available" -> Update URL
 if(p==='/api/update')return send(r,200,{ok:true,...updateInfo(u.searchParams.get('v')||u.searchParams.get('version')||'')});
 // ---------- APP API ----------
 if(p==='/api/app/login'&&q.method==='POST'){
  const b=await body(q),k=db.keys.find(x=>x.key===(b.key||'').trim());
  if(!k)return send(r,200,{ok:false,error:'invalid_key'});
  if(k.banned)return send(r,200,{ok:false,error:'banned'});
  if(k.device&&k.device!==b.device)return send(r,200,{ok:false,error:'device_mismatch'});
  if(!k.device){k.device=b.device;save()}
  if(k.expires&&k.expires<now())return send(r,200,{ok:false,error:'expired'});
  const t=rid(16);sessions[t]={key:k.key};
  return send(r,200,{ok:true,token:t,name:k.name,expires_epoch:k.expires||0,credits:k.credits})}
 if(p==='/api/app/config'){const k=userFrom(q);if(!k)return send(r,401,{ok:false,error:'session'});
  if(k.expires&&k.expires<now())return send(r,200,{ok:false,error:'expired'});
  return send(r,200,{ok:true,expires_epoch:k.expires||0,credits:k.credits,config:db.config,update:updateInfo(''),
   voice_profiles:db.voiceProfiles.map(v=>({id:v.id,name:v.name,provider:v.provider||'ElevenLabs',voice_id:v.voice_id,language:v.language||'bn',status:v.status||'ON',active:!!v.active})),active_voice_profile:activeProfile(),
   voices:db.voices.map(v=>({id:v.id,title:v.title,premium:v.premium,image:v.image?base(q)+'/files/'+v.image:'',audio:base(q)+'/files/'+v.audio})),tts:{enabled:!!ELEVENLABS_API_KEY,max_chars:TTS_MAX_CHARS,provider:'elevenlabs',default_voice_id:(activeProfile()&&activeProfile().voice_id)||db.config.tts_voice_id||ELEVENLABS_DEFAULT_VOICE_ID,model_id:db.config.tts_model_id||ELEVENLABS_MODEL_ID}})}
 if(p==='/api/app/tts'&&q.method==='POST'){
  const k=userFrom(q);if(!k)return send(r,401,{ok:false,error:'session'});
  if(!ELEVENLABS_API_KEY)return send(r,503,{ok:false,error:'tts_not_configured',message:'TTS provider is not configured on the server'});
  const b=await body(q),textValue=String(b.text||'').trim();
  // Voice Profile resolution: the app may send a profile id/name (Voice A) or a raw Voice ID.
  // An OFF profile is rejected; otherwise the profile's Voice ID is used for TTS.
  let voiceId=String(b.voice_id||b.voiceId||'').trim();
  if(voiceId){const prof=db.voiceProfiles.find(v=>v.id===voiceId||v.voice_id===voiceId||v.name===voiceId);
   if(prof){if(prof.status!=='ON')return send(r,400,{ok:false,error:'voice_profile_off',message:'This voice profile is OFF in Admin Panel.'});voiceId=prof.voice_id}}
  if(!voiceId){const ap=activeProfile();voiceId=(ap&&ap.voice_id)||db.config.tts_voice_id||ELEVENLABS_DEFAULT_VOICE_ID}
  voiceId=String(voiceId||'').trim();
  if(!textValue)return send(r,400,{ok:false,error:'text_required'});
  const selectedVoiceId=voiceId||db.config.tts_voice_id||ELEVENLABS_DEFAULT_VOICE_ID;
  if(!selectedVoiceId||!/^[A-Za-z0-9_-]{5,100}$/.test(selectedVoiceId))return send(r,400,{ok:false,error:'voice_id_required'});
  if(textValue.length>TTS_MAX_CHARS)return send(r,413,{ok:false,error:'text_too_long',max_chars:TTS_MAX_CHARS});
  if(k.expires&&k.expires<now())return send(r,403,{ok:false,error:'expired'});
  if(TTS_CREDITS_PER_REQUEST>0&&(Number(k.credits)||0)<TTS_CREDITS_PER_REQUEST)return send(r,402,{ok:false,error:'insufficient_credits'});
  const last=ttsLastRequest.get(k.key)||0;
  if(Date.now()-last<2500)return send(r,429,{ok:false,error:'rate_limited'});
  ttsLastRequest.set(k.key,Date.now());
  try{
   const allowedModels=new Set(['eleven_v3','eleven_multilingual_v2','eleven_flash_v2_5']);
   const requestedModel=String(b.model_id||db.config.tts_model_id||ELEVENLABS_MODEL_ID).slice(0,80);
   const model=allowedModels.has(requestedModel)?requestedModel:ELEVENLABS_MODEL_ID;
   const stability=Math.max(0,Math.min(1,Number(b.stability??0.5)));
   const similarityBoost=Math.max(0,Math.min(1,Number(b.similarity_boost??0.78)));
   const languageCode=/^[a-z]{2,3}$/.test(String(b.language_code||''))?String(b.language_code):undefined;
   const upstream=await fetch('https://api.elevenlabs.io/v1/text-to-speech/'+encodeURIComponent(selectedVoiceId)+'?output_format=mp3_44100_128',{
    method:'POST',headers:{'xi-api-key':ELEVENLABS_API_KEY,'Content-Type':'application/json','Accept':'audio/mpeg'},
    body:JSON.stringify({text:textValue,model_id:model,...(languageCode?{language_code:languageCode}:{}),voice_settings:{stability,similarity_boost:similarityBoost,style:0.15,use_speaker_boost:true}}),
    signal:AbortSignal.timeout(90000)
   });
   if(!upstream.ok){const msg=(await upstream.text()).slice(0,500);console.error('TTS provider error',upstream.status,msg);let err='tts_provider_error';try{const j=JSON.parse(msg);if(j.detail&&j.detail.code==='paid_plan_required')err='provider_paid_plan_required'}catch(_){} if(err==='tts_provider_error')err=upstream.status===401||upstream.status===403?'provider_auth_failed':upstream.status===402?'provider_quota':upstream.status===429?'provider_rate_limited':'tts_provider_error';return send(r,502,{ok:false,error:err,provider_status:upstream.status,message:'Voice provider rejected the request. Check Voice ID, API access and quota.'})}
   const audio=Buffer.from(await upstream.arrayBuffer());
   if(!audio.length)return send(r,502,{ok:false,error:'empty_audio'});
   const file=rid(12)+'.mp3';fs.writeFileSync(path.join(UP,file),audio);
   if(TTS_CREDITS_PER_REQUEST>0){k.credits=Math.max(0,(Number(k.credits)||0)-TTS_CREDITS_PER_REQUEST);save()}
   return send(r,200,{ok:true,id:file.replace(/\W/g,''),audio_url:base(q)+'/files/'+file,voice_id:selectedVoiceId,model_id:model,credits:k.credits,chars:textValue.length})
  }catch(e){console.error('TTS request failed',e.message);return send(r,502,{ok:false,error:'tts_request_failed',message:'Could not reach the voice provider. Try again later.'})}
 }
 if(p==='/api/app/redeem'&&q.method==='POST'){const k=userFrom(q);if(!k)return send(r,401,{ok:false});
  const b=await body(q),c=db.keys.find(x=>x.coupon&&x.coupon===b.coupon&&!x.used);
  const cp=(db.coupons||[]).find(x=>x.code===b.coupon&&!x.used);
  if(!cp)return send(r,200,{ok:false,error:'invalid_coupon'});
  cp.used=true;k.expires=Math.max(k.expires||now(),now())+cp.days*86400;k.credits+=cp.credits||0;save();
  return send(r,200,{ok:true,expires_epoch:k.expires,credits:k.credits})}
 // ---------- ADMIN API ----------
 if(p==='/admin/login'&&q.method==='POST'){const b=await body(q);if(b.pass!==ADMIN_PASS)return send(r,403,{ok:false});const t=rid(16);sessions[t]='admin';return send(r,200,{ok:true,token:t})}
 if(p.startsWith('/admin/')){
  if(!isAdmin(q))return send(r,403,{ok:false});
  if(p==='/admin/data')return send(r,200,{keys:db.keys,voices:db.voices,voiceProfiles:db.voiceProfiles,config:db.config,coupons:db.coupons||[],update:updateInfo(''),tts:{provider:'elevenlabs',configured:!!ELEVENLABS_API_KEY,model:db.config.tts_model_id||ELEVENLABS_MODEL_ID,max_chars:TTS_MAX_CHARS,credits_per_request:TTS_CREDITS_PER_REQUEST}});
  const b=q.method==='POST'?await body(q):{};
  if(p==='/admin/key/add'){const days=+b.days||30;const k={key:b.key||('ANM-'+rid(4).toUpperCase()),name:b.name||'',credits:+b.credits||0,expires:b.days==0?0:now()+days*86400,device:'',banned:false,created:now()};db.keys.push(k);save();return send(r,200,k)}
  if(p==='/admin/key/del'){db.keys=db.keys.filter(k=>k.key!==b.key);save();return send(r,200,{ok:true})}
  if(p==='/admin/key/edit'){const k=db.keys.find(x=>x.key===b.key);if(!k)return send(r,404,{});
   if('addDays'in b)k.expires=Math.max(k.expires||now(),now())+b.addDays*86400;
   if('addCredits'in b)k.credits+=+b.addCredits;if('banned'in b)k.banned=b.banned;if(b.resetDevice)k.device='';save();return send(r,200,k)}
  if(p==='/admin/coupon/add'){db.coupons=db.coupons||[];const c={code:'CP-'+rid(4).toUpperCase(),days:+b.days||7,credits:+b.credits||0,used:false};db.coupons.push(c);save();return send(r,200,c)}
  if(p==='/admin/voice/add'){ // body: {title,premium,audioB64,audioExt,imageB64,imageExt}
   const v={id:rid(6),title:b.title||'Voice',premium:!!b.premium,audio:'',image:''};
   if(b.audioB64){v.audio=rid(8)+'.'+(b.audioExt||'mp3').replace(/\W/g,'');fs.writeFileSync(path.join(UP,v.audio),Buffer.from(b.audioB64,'base64'))}
   if(b.imageB64){v.image=rid(8)+'.'+(b.imageExt||'png').replace(/\W/g,'');fs.writeFileSync(path.join(UP,v.image),Buffer.from(b.imageB64,'base64'))}
   db.voices.push(v);save();return send(r,200,v)}
  if(p==='/admin/voice/del'){db.voices=db.voices.filter(v=>v.id!==b.id);save();return send(r,200,{ok:true})}
  // ---------- Voice Profiles (Display Name / Provider / Voice ID / Language / Status) ----------
  if(p==='/admin/voiceprofile/add'){const v={id:rid(6),name:String(b.name||'Voice').slice(0,60),provider:String(b.provider||'ElevenLabs').slice(0,40),voice_id:String(b.voice_id||'').trim().slice(0,100),language:String(b.language||'bn').slice(0,5),status:b.status==='OFF'?'OFF':'ON',active:!!b.active};if(!v.voice_id)return send(r,400,{ok:false,error:'voice_id_required'});if(v.active){db.voiceProfiles.forEach(x=>x.active=false);db.config.active_voice_profile_id=v.id;db.config.tts_voice_id=v.voice_id}db.voiceProfiles.push(v);save();return send(r,200,v)}
  if(p==='/admin/voiceprofile/del'){db.voiceProfiles=db.voiceProfiles.filter(v=>v.id!==b.id);if(db.config.active_voice_profile_id===b.id)db.config.active_voice_profile_id='';save();return send(r,200,{ok:true})}
  if(p==='/admin/voiceprofile/edit'){const v=db.voiceProfiles.find(x=>x.id===b.id);if(!v)return send(r,404,{});
   if(b.name!==undefined)v.name=String(b.name).slice(0,60);if(b.provider!==undefined)v.provider=String(b.provider).slice(0,40);
   if(b.voice_id!==undefined)v.voice_id=String(b.voice_id).trim().slice(0,100);if(b.language!==undefined)v.language=String(b.language).slice(0,5);
   if(b.status!==undefined)v.status=b.status==='OFF'?'OFF':'ON';
   if(b.active){db.voiceProfiles.forEach(x=>x.active=false);v.active=true;db.config.active_voice_profile_id=v.id;if(v.status==='ON')db.config.tts_voice_id=v.voice_id}
   save();return send(r,200,v)}
  if(p==='/admin/config'){Object.assign(db.config,b);save();return send(r,200,db.config)}
 }
 send(r,404,{error:'not_found'});
 }catch(e){console.error(e);send(r,500,{error:String(e)})}
}).listen(PORT,()=>console.log('AS ANAMUL VOICE server listening on '+PORT));
