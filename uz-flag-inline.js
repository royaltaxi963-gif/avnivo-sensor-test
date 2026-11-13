
(()=>{const c=document.getElementById('uzFlagCanvas'),gl=c.getContext('webgl2',{alpha:true,antialias:true});if(!gl)return;const vs=`#version 300 es
precision highp float;in vec2 uv;uniform float t;uniform float ratio;out vec2 v;out vec3 p;void main(){
float u=uv.x;
float vcoord=uv.y;
float x=u*3.4;
float y=(.5-vcoord)*1.7;
float release=smoothstep(0.,.22,u);
float phase=x*2.65-t*2.25+.14*sin(t*.65);
float breeze=.96+.10*sin(t*.73)+.045*sin(t*1.41);
float wave=release*breeze*(.30*sin(phase)+.065*sin(phase*1.8-.8+y*.7));
float edge=pow(u,1.65);
float xx=x-.09*release*(1.-cos(phase))-.07*edge*sin(t*2.25+vcoord*2.3);
float yy=y+.075*release*sin(phase-.7)+.055*edge*sin(t*2.25+vcoord*3.2);
float zz=wave;
p=vec3(xx,yy,zz);v=uv;
float perspective=1.-.16*zz;
float sx=1.65/3.9;
float sy=1.05/2.2;
gl_Position=vec4((xx-1.7)*sx*perspective,yy*sy*perspective,zz*.03,1.);
}`;const fs=`#version 300 es
precision highp float;in vec2 v;in vec3 p;uniform sampler2D tex;out vec4 o;void main(){vec3 n=normalize(cross(dFdx(p),dFdy(p)));if(n.z<0.)n=-n;float l=.52+.62*max(dot(n,normalize(vec3(-.4,.6,1.4))),0.);o=vec4(texture(tex,v).rgb*l,1.);}`;function sh(type,src){let a=gl.createShader(type);gl.shaderSource(a,src);gl.compileShader(a);if(!gl.getShaderParameter(a,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(a));return a}let prog=gl.createProgram();gl.attachShader(prog,sh(gl.VERTEX_SHADER,vs));gl.attachShader(prog,sh(gl.FRAGMENT_SHADER,fs));gl.linkProgram(prog);if(!gl.getProgramParameter(prog,gl.LINK_STATUS))return;gl.useProgram(prog);const W=320,H=180,vertices=new Float32Array((W+1)*(H+1)*2),idx=new Uint32Array(W*H*6);let a=0,k=0;for(let j=0;j<=H;j++)for(let i=0;i<=W;i++){vertices[a++]=i/W;vertices[a++]=j/H}for(let j=0;j<H;j++)for(let i=0;i<W;i++){let q=j*(W+1)+i,b=q+1,d=q+W+1;idx[k++]=q;idx[k++]=d;idx[k++]=b;idx[k++]=b;idx[k++]=d;idx[k++]=d+1}const vao=gl.createVertexArray();gl.bindVertexArray(vao);let b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,vertices,gl.STATIC_DRAW);let loc=gl.getAttribLocation(prog,'uv');gl.enableVertexAttribArray(loc);gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);b=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,b);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,idx,gl.STATIC_DRAW);const tc=document.createElement('canvas');tc.width=3072;tc.height=1536;const g=tc.getContext('2d'),w=tc.width,h=tc.height;g.fillStyle='#0099b5';g.fillRect(0,0,w,h/3);g.fillStyle='#fff';g.fillRect(0,h/3,w,h/3);g.fillStyle='#1eb44b';g.fillRect(0,2*h/3,w,h/3);g.fillStyle='#d5263c';g.fillRect(0,h/3-h*.009,w,h*.018);g.fillRect(0,2*h/3-h*.009,w,h*.018);let cx=w*.13,cy=h*.15,r=h*.125;g.fillStyle='#fff';g.beginPath();g.arc(cx,cy,r,0,Math.PI*2);g.fill();g.fillStyle='#0099b5';g.beginPath();g.arc(cx+r*.39,cy-r*.12,r*.82,0,Math.PI*2);g.fill();function star(x,y,r){g.fillStyle='#fff';g.beginPath();for(let q=0;q<10;q++){let an=-Math.PI/2+q*Math.PI/5,rr=q%2?r*.42:r;if(!q)g.moveTo(x+Math.cos(an)*rr,y+Math.sin(an)*rr);else g.lineTo(x+Math.cos(an)*rr,y+Math.sin(an)*rr)}g.closePath();g.fill()}for(let row=0;row<3;row++)for(let col=0;col<3+row;col++)star(w*.265+col*w*.052,h*.07+row*h*.078,h*.023);let tx=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tx);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,tc);gl.generateMipmap(gl.TEXTURE_2D);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR_MIPMAP_LINEAR);gl.uniform1i(gl.getUniformLocation(prog,'tex'),0);const t=gl.getUniformLocation(prog,'t'),ratio=gl.getUniformLocation(prog,'ratio');let frame=0;function draw(ms){let d=Math.min(devicePixelRatio||1,3),cw=Math.max(1,Math.round(c.clientWidth*d)),ch=Math.max(1,Math.round(c.clientHeight*d));if(c.width!==cw||c.height!==ch){c.width=cw;c.height=ch;gl.viewport(0,0,cw,ch)}gl.clearColor(0,0,0,0);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.enable(gl.DEPTH_TEST);gl.disable(gl.CULL_FACE);gl.uniform1f(t,ms*.001);gl.uniform1f(ratio,cw/ch);gl.drawElements(gl.TRIANGLES,idx.length,gl.UNSIGNED_INT,0);frame=requestAnimationFrame(draw)}document.addEventListener('visibilitychange',()=>{if(document.hidden)cancelAnimationFrame(frame);else frame=requestAnimationFrame(draw)});frame=requestAnimationFrame(draw)})();
