// Original vector symbols for learning links. No remote assets or tracking.
export const siteIconChoices = [
 ['science','实验','#0f8d8d','M9 3h6M10 3v6L4 19q-1 2 2 2h12q3 0 2-2L14 9V3M8 14h8M9 17h.1M14 18h.1'],
 ['book','阅读','#c57b32','M12 5v16M12 5Q7 2 2 4v15q6-2 10 2 4-4 10-2V4q-5-2-10 1'],
 ['globe','世界','#397ed4','M3 12h18M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M12 3q-7 9 0 18 7-9 0-18'],
 ['math','数学','#659d45','M4 6h6M7 3v6M15 6h6M4 15l6 6M10 15l-6 6M15 16h6M15 20h6'],
 ['music','音乐','#e36d61','M9 18V5l12-2v13M9 8l12-2M9 18a3 3 0 1 1-3-3q3 0 3 3M21 16a3 3 0 1 1-3-3q3 0 3 3'],
 ['notebook','笔记','#8b68cb','M5 3h14v18H5zM3 7h4M3 12h4M3 17h4M10 8h5M10 12h5M10 16h3'],
 ['rocket','起航','#ec763d','M8 16l-4 4M5 15l-2-2 5-4M9 19l2 2 4-5M8 16C7 7 14 3 21 3c0 7-4 14-13 13ZM14 8a2 2 0 1 0 4 0 2 2 0 0 0-4 0'],
 ['planet','星球','#7266d2','M18 7a8 8 0 1 0 1 9M4 13C-3 21 8 24 19 13S22 0 15 6M14 5a8 8 0 0 1 5 5'],
 ['star','闪耀','#cf931d','m12 2 3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1Z'],
 ['puzzle','拼图','#e37786','M3 3h6a3 3 0 1 0 6 0h6v6a3 3 0 1 0 0 6v6h-6a3 3 0 1 1-6 0H3v-6a3 3 0 1 0 0-6Z'],
 ['paint','创意','#b660b4','M12 3a9 9 0 1 0 0 18c4 0 2-3 4-4 2-2 5 0 5-5a9 9 0 0 0-9-9ZM7 8h.1M12 6h.1M17 9h.1M6 13h.1'],
 ['leaf','自然','#3b9b74','M3 21l13-13M5 18C-1 8 9 2 21 3c0 11-5 20-16 15ZM8 13h7M11 10V6'],
 ['sun','阳光','#d3891d','M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10M12 1v2M12 21v2M1 12h2M21 12h2M4 4l2 2M18 18l2 2M4 20l2-2M18 6l2-2'],
 ['rainbow','彩虹','#bd6795','M2 21v-3a10 10 0 0 1 20 0v3M6 21v-3a6 6 0 0 1 12 0v3M10 21v-3a2 2 0 0 1 4 0v3'],
 ['compass','发现','#3d9eb4','M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M16 8l-2 6-6 2 2-6Z'],
 ['trophy','成长','#bd882c','M7 3h10v7a5 5 0 0 1-10 0ZM7 5H3v3a4 4 0 0 0 4 4M17 5h4v3a4 4 0 0 1-4 4M12 15v6M8 21h8'],
 ['bulb','灵感','#c79522','M9 18h6M9 21h6M8 15c-7-6-2-13 4-13s11 7 4 13l-1 3H9ZM12 6v4M10 10l2 2 2-2'],
 ['atom','科学','#427ed0','M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4M3 6c-4 7 15 18 18 12S6 0 3 6M3 18C-1 11 18 0 21 6S6 24 3 18M12 2c-6 0-6 20 0 20s6-20 0-20'],
 ['cloud','想象','#498fb4','M6 19h12a4 4 0 0 0 1-8 7 7 0 0 0-13-2 5 5 0 0 0 0 10'],
 ['mountain','远方','#538f75','m2 21 7-14 4 7 3-5 6 12ZM6 13l3 2 2-2'],
 ['camera','影像','#c76a68','M3 6h5l2-3h4l2 3h5v15H3ZM8 13a4 4 0 1 0 8 0 4 4 0 0 0-8 0'],
 ['language','语言','#507fb6','M3 4h12M9 2v3M5 7q2 6 8 8M13 5q-1 7-10 11M13 21l4-12 5 12M15 17h5'],
 ['code','编程','#7768ba','m8 6-6 6 6 6M16 6l6 6-6 6M14 3l-4 18'],
 ['heart','热爱','#d26785','M12 21 3 12C-3 3 8-1 12 6c4-7 15-3 9 6Z'],
].map(([id,name,color,path])=>({id,name,color,path}));
export function siteIconData(id){return siteIconChoices.find(x=>x.id===id)||siteIconChoices[2];}
export function renderSiteIcon(id){const x=siteIconData(id);return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+x.path+'"/></svg>';}
