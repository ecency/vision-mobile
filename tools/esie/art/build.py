import json, sys
P = json.load(open(sys.argv[1])); out = sys.argv[2]
def d(name, k=0): return P[name]['paths'][k]['d']
def bb(name, k=0): return P[name]['paths'][k]['bbox']
ew = sorted(P['eye_whites']['paths'], key=lambda q: q['bbox'][0])
ears = sorted(P['ears']['paths'], key=lambda q: q['bbox'][0])
def eye(side, white, pupil):
    wx0, wy0, wx1, wy1 = white['bbox']
    x0, y0, x1, y1 = bb(pupil); w, h = x1 - x0, y1 - y0
    hx, hy = x0 + .70 * w, y0 + .22 * h
    return f'''<g id="eye_{side}">
  <path id="eye_{side}_white" d="{white['d']}" fill="url(#gWhite)"/>
  <g id="eye_{side}_pupil">
    <path d="{d(pupil)}" fill="url(#gPupil)"/>
    <ellipse id="eye_{side}_shine" cx="{hx:.0f}" cy="{hy:.0f}" rx="{w*.07:.0f}" ry="{h*.08:.0f}" fill="#fff"/>
  </g>
  <clipPath id="c_eye_{side}"><path d="{white['d']}"/></clipPath>
  <g clip-path="url(#c_eye_{side})"><g id="eye_{side}_lid" class="lid" style="--up:-{wy1-wy0+20:.0f}px;transform:translateY(var(--up))">
    <rect x="{wx0-20:.0f}" y="{wy0-20:.0f}" width="{wx1-wx0+40:.0f}" height="{wy1-wy0+20:.0f}" fill="#0a47c4"/>
    <rect x="{wx0-20:.0f}" y="{wy1-14:.0f}" width="{wx1-wx0+40:.0f}" height="14" fill="#00226a"/>
  </g></g>
</g>'''
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="120 250 1740 1530" width="870" height="765">
<defs>
  <radialGradient id="gBody" cx=".38" cy=".28" r=".8"><stop offset="0" stop-color="#1f66e8"/><stop offset=".55" stop-color="#0041c2"/><stop offset="1" stop-color="#00226a"/></radialGradient>
  <radialGradient id="gFin" cx=".45" cy=".35" r=".75"><stop offset="0" stop-color="#1a63e6"/><stop offset=".6" stop-color="#003db5"/><stop offset="1" stop-color="#00226a"/></radialGradient>
  <linearGradient id="gTail" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0047c9"/><stop offset="1" stop-color="#00205f"/></linearGradient>
  <linearGradient id="gEar" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#00133f"/><stop offset="1" stop-color="#002a85"/></linearGradient>
  <radialGradient id="gWhite" cx=".45" cy=".4" r=".7"><stop offset=".55" stop-color="#ffffff"/><stop offset="1" stop-color="#cdd0e6"/></radialGradient>
  <radialGradient id="gPupil" cx=".35" cy=".72" r=".45"><stop offset="0" stop-color="#1446b8"/><stop offset=".7" stop-color="#030a24"/><stop offset="1" stop-color="#01040f"/></radialGradient>
  <linearGradient id="gMuzzle" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#f6f7fd"/><stop offset=".55" stop-color="#e6ecf9"/><stop offset="1" stop-color="#b9cdee"/></linearGradient>
  <radialGradient id="gBlush" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="#ff9fb1" stop-opacity=".75"/><stop offset="1" stop-color="#ff9fb1" stop-opacity="0"/></radialGradient>
  <linearGradient id="gBelly" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7fa9e2"/><stop offset=".75" stop-color="#eef3fc"/></linearGradient>
  <clipPath id="cBody"><path d="{d('head')}"/></clipPath>
  <clipPath id="cMuzzle"><path d="{d('muzzle')}"/></clipPath>
  <filter id="fSoft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="14"/></filter>
</defs>
<g id="esie">
  <g id="tail"><path id="tail_body" d="{d('tail_body')}" fill="url(#gTail)"/></g>
  <g id="body">
    <path id="body_fill" d="{d('head')}" fill="url(#gBody)"/>
    <g id="body_shade" clip-path="url(#cBody)" opacity=".55">{''.join(f'<path d="{q["d"]}" fill="#000c34" filter="url(#fSoft)"/>' for q in P['head_shade']['paths'])}</g>
  </g>
  <path id="tail_tip" d="{d('tail_curl')}" fill="url(#gTail)"/>
  {eye('l', ew[0], 'pupil_l')}
  {eye('r', ew[1], 'pupil_r')}
  <g id="eyes_happy" style="display:none">
    <path d="M748,792 C790,700 892,690 940,770" stroke="#031033" stroke-width="40" stroke-linecap="round" fill="none"/>
    <path d="M1296,668 C1336,578 1440,566 1486,640" stroke="#031033" stroke-width="40" stroke-linecap="round" fill="none"/>
  </g>
  <g id="lids_sad" style="display:none">
    <g clip-path="url(#c_eye_l)"><path d="M680,560 L1080,560 L1080,688 C980,716 860,756 680,804 Z" fill="#0a47c4"/><path d="M680,804 C860,756 980,716 1080,688" stroke="#00226a" stroke-width="14" fill="none"/></g>
    <g clip-path="url(#c_eye_r)"><path d="M1250,440 L1640,440 L1640,700 C1520,672 1400,640 1250,604 Z" fill="#0a47c4"/><path d="M1250,604 C1400,640 1520,672 1640,700" stroke="#00226a" stroke-width="14" fill="none"/></g>
  </g>
  <g id="brows_think" style="display:none">
    <path d="M756,604 C812,592 872,584 934,586" stroke="#031033" stroke-width="30" stroke-linecap="round" fill="none"/>
    <path d="M1296,452 C1340,398 1430,388 1494,428" stroke="#031033" stroke-width="30" stroke-linecap="round" fill="none"/>
  </g>
  <g id="brows_surprised" style="display:none">
    <path d="M744,566 C784,494 876,480 936,520" stroke="#031033" stroke-width="30" stroke-linecap="round" fill="none"/>
    <path d="M1292,430 C1330,362 1428,348 1492,392" stroke="#031033" stroke-width="30" stroke-linecap="round" fill="none"/>
  </g>
  <g id="brows_sad" style="display:none">
    <path d="M748,612 C800,586 868,560 930,520" stroke="#031033" stroke-width="30" stroke-linecap="round" fill="none"/>
    <path d="M1300,430 C1372,440 1432,460 1494,500" stroke="#031033" stroke-width="30" stroke-linecap="round" fill="none"/>
  </g>
  <g id="mouth">
    <path id="mouth_smile" class="mouth" d="{d('mouth')}" fill="#d98c9a"/>
    <g id="mouth_open" class="mouth" opacity="0"><path d="M1118,826 C1140,826 1212,822 1236,820 C1236,872 1206,902 1176,902 C1144,902 1118,870 1118,826Z" fill="#5a0f2a"/><path d="M1146,880 C1160,866 1196,866 1210,878 C1200,896 1158,898 1146,880Z" fill="#e8798f"/></g>
    <ellipse id="mouth_o" class="mouth" opacity="0" cx="1178" cy="854" rx="34" ry="44" fill="#5a0f2a"/>
    <g id="mouth_big" class="mouth" opacity="0"><path d="M1096,812 C1150,826 1214,816 1262,796 C1262,876 1222,930 1172,930 C1124,930 1094,880 1096,812Z" fill="#5a0f2a"/><path d="M1124,890 C1150,862 1212,860 1236,880 C1218,918 1146,922 1124,890Z" fill="#e8798f"/></g>
    <path id="mouth_frown" class="mouth" opacity="0" d="M1128,872 C1150,838 1206,826 1232,850" stroke="#d98c9a" stroke-width="16" stroke-linecap="round" fill="none"/>
    <path id="mouth_hmm" class="mouth" opacity="0" d="M1140,858 C1160,842 1180,842 1196,854 C1212,866 1230,864 1244,844" stroke="#d98c9a" stroke-width="16" stroke-linecap="round" fill="none"/>
    <path id="mouth_flat" class="mouth" opacity="0" d="M1130,846 C1160,852 1200,852 1228,844" stroke="#d98c9a" stroke-width="16" stroke-linecap="round" fill="none"/>
  </g>
  <g id="ears"><path id="ear_l" d="{ears[0]['d']}" fill="url(#gEar)"/><path id="ear_r" d="{ears[1]['d']}" fill="url(#gEar)"/></g>
  <path id="belly" d="{d('belly')}" fill="url(#gBelly)"/>
  <path id="navel" d="{d('navel')}" fill="#001f64"/>
  <path id="fin_back" d="{d('fin_back')}" fill="url(#gFin)"/>
  <g id="fin_wave" style="display:none">
    <path id="fin_wave_arm" d="M800,1010 C740,930 676,846 622,772 C596,736 536,736 508,764 C482,792 488,830 512,866 C566,946 640,1040 700,1120 Z" fill="url(#gFin)"/>
    <g id="fin_wave_hand">
      <path d="M612,792 C640,700 630,560 572,470 C530,404 448,380 398,418 C344,460 348,560 384,650 C420,740 470,800 520,822 C560,840 600,830 612,792Z" fill="url(#gFin)"/>
      <path d="M430,470 C470,520 500,600 512,680" stroke="#00266f" stroke-opacity=".28" stroke-width="12" stroke-linecap="round" fill="none"/>
    </g>
  </g>
  <g id="fin_rest" style="display:none">
    <path d="{d('tail_curl')}" fill="url(#gTail)" transform="translate(-550,70) rotate(-8 1555 1323) translate(3110,0) scale(-1,1)"/>
  </g>
  <g id="muzzle">
    <path id="muzzle_fill" d="{d('muzzle')}" fill="url(#gMuzzle)"/>
    <g clip-path="url(#cMuzzle)">
      <path id="muzzle_shade" d="{d('fin_shade')}" fill="#00339a" opacity=".18" filter="url(#fSoft)"/>
      <ellipse id="blush_r" cx="1720" cy="965" rx="120" ry="95" fill="url(#gBlush)"/>
    </g>
  </g>
  <path id="fin_front" d="{d('fin_thumb')}" fill="url(#gFin)"/>
  <path id="tear" style="display:none" d="M760,930 C742,968 730,996 730,1016 C730,1040 748,1056 770,1056 C792,1056 808,1040 808,1016 C808,994 790,966 760,930Z" fill="#8fc3ff" stroke="#ffffff" stroke-opacity=".7" stroke-width="6"/>
  <g id="thought" style="display:none">
    <circle id="thought_1" cx="1712" cy="560" r="24" fill="#eef4ff" stroke="#9abbc5" stroke-width="6"/>
    <circle id="thought_2" cx="1760" cy="470" r="36" fill="#eef4ff" stroke="#9abbc5" stroke-width="6"/>
    <circle id="thought_3" cx="1790" cy="350" r="52" fill="#eef4ff" stroke="#9abbc5" stroke-width="6"/>
  </g>
</g>
</svg>'''
open(out, 'w').write(svg)
