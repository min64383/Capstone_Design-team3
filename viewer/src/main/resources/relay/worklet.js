// 데모 중계 페이지의 링 버퍼(M66 설계 3.6절). 서버가 보낸 16-bit 스테레오 PCM을 받아 bufferMs만큼 채운 뒤 재생한다.
// 비면 0을 내고 빈 횟수를 세고 다시 채운다. maxBufferMs를 넘게 쌓이면 bufferMs만 남기고 버린다(폰 오디오 시계가 느릴 때).
// 브라우저 표본율이 서버와 다르면 비율대로 건너뛰며 읽는다(선형). 같으면 받은 표본을 그대로 낸다.
// 재생한 입력 프레임 수를 페이지에 알려, 페이지가 같은 프레임 번호의 화면을 띄우게 한다.
class Pcm extends AudioWorkletProcessor {
  constructor(o) {
    super();
    const p = o.processorOptions;
    this.ratio = p.inRate / sampleRate; // 출력 표본 하나에 읽는 입력 프레임 수
    this.cap = Math.ceil(p.inRate * 4);
    this.L = new Float32Array(this.cap);
    this.R = new Float32Array(this.cap);
    this.target = Math.round(p.bufferMs * p.inRate / 1000);
    this.max = Math.round(p.maxBufferMs * p.inRate / 1000);
    this.underruns = 0;
    this.drops = 0;
    this.reset();
    this.port.onmessage = (e) => (e.data && e.data.reset ? this.reset() : this.push(e.data));
  }

  reset() {
    this.wp = 0; // 쓴 입력 프레임(스트림 시작부터)
    this.rp = 0; // 읽은 위치(소수)
    this.playing = false;
    this.rest = null; // 4바이트 프레임에 모자라 남은 바이트
    this.tick = 0;
  }

  push(buf) {
    let b = new Uint8Array(buf);
    if (this.rest) {
      const j = new Uint8Array(this.rest.length + b.length);
      j.set(this.rest);
      j.set(b, this.rest.length);
      b = j;
      this.rest = null;
    }
    const frames = b.length >> 2;
    if (b.length & 3) this.rest = b.slice(frames << 2);
    const v = new DataView(b.buffer, b.byteOffset, frames << 2);
    if (this.wp + frames - this.rp > this.cap - 1) { this.rp = this.wp + frames - this.target; this.drops++; }
    for (let i = 0; i < frames; i++) {
      const k = this.wp % this.cap;
      this.L[k] = v.getInt16(4 * i, true) / 32768;
      this.R[k] = v.getInt16(4 * i + 2, true) / 32768;
      this.wp++;
    }
  }

  process(_, outputs) {
    const out = outputs[0];
    const l = out[0];
    const r = out[1] || out[0];
    if (!this.playing && this.wp - this.rp >= this.target) this.playing = true;
    if (this.playing && this.wp - this.rp > this.max) { this.rp = this.wp - this.target; this.drops++; }
    for (let i = 0; i < l.length; i++) {
      if (!this.playing) { l[i] = 0; r[i] = 0; continue; }
      if (this.wp - this.rp < 1 + this.ratio) { // 비었다: 0을 내고 다시 채운다
        this.playing = false;
        this.underruns++;
        l[i] = 0; r[i] = 0;
        continue;
      }
      const i0 = Math.floor(this.rp);
      const f = this.rp - i0;
      const a = i0 % this.cap;
      if (f === 0) { l[i] = this.L[a]; r[i] = this.R[a]; } else {
        const c = (i0 + 1) % this.cap;
        l[i] = this.L[a] + (this.L[c] - this.L[a]) * f;
        r[i] = this.R[a] + (this.R[c] - this.R[a]) * f;
      }
      this.rp += this.ratio;
    }
    if (++this.tick % 16 === 0) {
      this.port.postMessage({ played: Math.floor(this.rp), buffered: this.wp - this.rp, underruns: this.underruns, drops: this.drops });
    }
    return true;
  }
}
registerProcessor('pcm', Pcm);
