'use strict';
// 앱 작동 화면 그리기(M45 UserModeActivity·GuideView와 같은 규칙). 중계 페이지(index.html)와 영상 데모 페이지(demo.html)가 같이 쓴다(M73).
// 페이지에는 #title #dot #titleText #sub #guide #cv #label #hints #notice가 있어야 한다.
const $ = (id) => document.getElementById(id);
const COLOR = { text: '#FFFFFF', caution: '#EAB431', stop: '#F26A5F' };
const RING = ['#DE867C', '#D2AC5A', '#6AB67F', '#53ADDC', '#8E9CE7', '#D884A5']; // logo_1~6
const still = matchMedia('(prefers-reduced-motion: reduce)').matches; // 앱은 애니메이션 끔 설정이면 준비 고리를 멈춘다
const logo = new Image();
logo.onload = () => draw();
logo.src = 'logo.png';

// ---- 화면 (앱 UserModeActivity.show()와 같은 규칙) ----
let shown = null;
let visual = { visual: 'None' };

/** 앱 화면 요소 하나(서버가 보낸 UserScreen JSON)를 그린다. 설정 단추는 없다. */
function showApp(s) {
  shown = s;
  const title = $('title');
  title.hidden = s.title == null;
  $('titleText').textContent = s.title || '';
  title.style.color = s.titleTone === 'CAUTION' ? COLOR.caution : COLOR.text;
  $('dot').hidden = s.titleTone !== 'GUIDE';
  $('sub').hidden = s.sub == null;
  $('sub').textContent = s.sub || '';
  // 그림 칸: 가장 큰 그림(화살표 230)이 들어가는 240, 시작 전 로고는 176
  $('guide').style.flexBasis = (s.visual === 'Logo' ? 176 : 240) + 'px';
  // 페이지에서 할 수 없는 조작(두 번 탭·길게 누르기)의 글은 쓰지 않는다: 앱 안내 줄(s.hints)은 비워 두고(#hints는 빈 자리),
  // 앱 시작 전 큰 글 "두 번 탭하여 시작"은 그 상태의 TalkBack 문장("시작 전")으로. 페이지 자체 화면(showStart)은 spoken이 없어 그대로
  const text = s.idle && s.visual === 'Logo' && s.spoken ? s.spoken : s.label;
  const label = $('label');
  label.hidden = text == null;
  label.textContent = text || '';
  label.style.fontSize = (s.labelTone === 'STOP' ? 36 : s.labelTone === 'CAUTION' ? 30 : 34) + 'px';
  label.style.color = text == null || s.visual !== 'Arrow' ? COLOR.text : s.labelTone === 'STOP' ? COLOR.stop : COLOR.caution;
  $('notice').hidden = !(s.idle && s.visual === 'Logo' && s.notice);
  $('notice').textContent = s.notice || '';
  visual = s;
  draw();
}

/** 중계에만 있는 화면: 시작 화면(로고 + "탭하여 듣기 시작")과 "중계 끊김". */
function showStart() {
  showApp({ title: null, sub: null, titleTone: 'PLAIN', visual: 'Logo', label: '탭하여\n듣기 시작', labelTone: 'PLAIN', hints: [], idle: true,
    notice: '이어폰을 끼고 들어 주세요. 아이폰은 무음 스위치를 꺼 주세요.' });
}
function showLost(serverDown) {
  showApp({ title: '중계 끊김', sub: serverDown ? '서버와 연결이 끊겼어요' : 'S10에서 ' + Math.round(S.lostMs / 1000) + '초 넘게 소식이 없어요',
    titleTone: 'PLAIN', visual: 'None', label: null, labelTone: 'PLAIN', hints: [], idle: false, notice: null });
}

// ---- 가운데 그림 (앱 GuideView와 같은 120칸 기준 도형) ----
const cv = $('cv');
let anim = 0;
function draw() {
  const box = cv.getBoundingClientRect();
  const dpr = window.devicePixelRatio || 1;
  if (cv.width !== Math.round(box.width * dpr) || cv.height !== Math.round(box.height * dpr)) {
    cv.width = Math.round(box.width * dpr);
    cv.height = Math.round(box.height * dpr);
  }
  const g = cv.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, box.width, box.height);
  g.lineCap = 'round';
  g.lineJoin = 'round';
  const cx = box.width / 2, cy = box.height / 2, room = Math.min(box.width, box.height);
  const v = visual;
  let u = 0;
  cancelAnimationFrame(anim);
  switch (v.visual) {
    case 'Logo': {
      const s = Math.min(150, room);
      u = s / 150;
      if (logo.complete && logo.naturalWidth) g.drawImage(logo, cx - s / 2, cy - s / 2, s, s);
      break;
    }
    case 'Preparing': { // 로고 여섯 색이 차례로 켜지는 고리: 원 지름 26, 고리 반지름 42
      u = Math.min(180, room) / 120;
      const t = performance.now();
      for (let i = 0; i < 6; i++) {
        const a = (-90 + 60 * i) * Math.PI / 180;
        g.globalAlpha = still ? 1 : pulse((((t - i * 300) % 1800) + 1800) % 1800 / 1800);
        g.fillStyle = RING[i];
        g.beginPath();
        g.arc(cx + 42 * u * Math.cos(a), cy + 42 * u * Math.sin(a), 13 * u, 0, 2 * Math.PI);
        g.fill();
      }
      g.globalAlpha = 1;
      if (!still) anim = requestAnimationFrame(draw);
      break;
    }
    case 'FloorCheck': { // 주의 세모 + 느낌표(100칸, 150)
      u = Math.min(150, room) / 100;
      g.translate(cx - 50 * u, cy - 46 * u);
      g.strokeStyle = COLOR.caution;
      g.lineWidth = 8 * u;
      g.beginPath();
      g.moveTo(50 * u, 6 * u); g.lineTo(95 * u, 86 * u); g.lineTo(5 * u, 86 * u); g.closePath();
      g.stroke();
      exclamation(g, u, 34, 58, 72, COLOR.caution);
      break;
    }
    case 'Paused': {
      u = Math.min(120, room) / 100;
      g.fillStyle = COLOR.text;
      g.translate(cx - 50 * u, cy - 50 * u);
      for (const left of [22, 60]) { g.beginPath(); g.roundRect(left * u, 14 * u, 18 * u, 72 * u, 6 * u); g.fill(); }
      break;
    }
    case 'Notice': { // 흰 원 + 느낌표
      u = Math.min(120, room) / 100;
      g.translate(cx - 50 * u, cy - 50 * u);
      g.strokeStyle = COLOR.text;
      g.lineWidth = 8 * u;
      g.beginPath(); g.arc(50 * u, 50 * u, 42 * u, 0, 2 * Math.PI); g.stroke();
      exclamation(g, u, 28, 56, 71, COLOR.text);
      break;
    }
    case 'Arrow': { // 120칸(230). 주의: 화살표만. 멈춤: 굵은 고리 + 작은 화살표
      u = Math.min(230, room) / 120;
      const color = v.stop ? COLOR.stop : COLOR.caution;
      g.translate(cx, cy);
      if (v.stop) { g.strokeStyle = color; g.lineWidth = 9 * u; g.beginPath(); g.arc(0, 0, 54 * u, 0, 2 * Math.PI); g.stroke(); }
      g.rotate(v.az * Math.PI / 180);
      const tip = v.stop ? -36 : -46, head = v.stop ? -11 : -16, shaft = v.stop ? 7 : 8, tail = v.stop ? 32 : 40, half = v.stop ? 20 : 24;
      g.beginPath();
      g.moveTo(0, tip * u);
      g.lineTo(-half * u, head * u); g.lineTo(-shaft * u, head * u); g.lineTo(-shaft * u, tail * u);
      g.lineTo(shaft * u, tail * u); g.lineTo(shaft * u, head * u); g.lineTo(half * u, head * u);
      g.closePath();
      g.fillStyle = color; g.strokeStyle = color; g.lineWidth = 5 * u;
      g.fill(); g.stroke();
      break;
    }
  }
  cv.dataset.visual = v.visual;
  cv.dataset.az = v.visual === 'Arrow' ? String(v.az) : '';
  cv.dataset.stop = v.visual === 'Arrow' ? String(v.stop) : '';
  cv.dataset.u = String(u);
}
function exclamation(g, u, top, bottom, dot, color) {
  g.strokeStyle = color; g.lineWidth = 9 * u;
  g.beginPath(); g.moveTo(50 * u, top * u); g.lineTo(50 * u, bottom * u); g.stroke();
  g.fillStyle = color; g.beginPath(); g.arc(50 * u, dot * u, 5.5 * u, 0, 2 * Math.PI); g.fill();
}
// 앱 시안의 켜짐 곡선: 15%에서 가장 밝고 천천히 어두워진다(투명도 0.25 ~ 1)
function pulse(f) { return f < 0.15 ? 0.25 + 0.75 * f / 0.15 : 1 - 0.75 * (f - 0.15) / 0.85; }
new ResizeObserver(() => draw()).observe($('guide'));
