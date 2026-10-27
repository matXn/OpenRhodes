"""按整张 PNG 的亚像素边缘描摹，不读取字体、不逐字排版。"""
from pathlib import Path
from collections import defaultdict
import json, hashlib
import numpy as np
import cv2
from PIL import Image
from matplotlib.path import Path as VectorPath
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'artwork/openrhodes-source.png'

def trace(coverage):
    values=np.pad(coverage,1)
    a,b,c,d=values[:-1,:-1],values[:-1,1:],values[1:,1:],values[1:,:-1]
    cases=(a>=.5).astype(np.uint8)+2*(b>=.5)+4*(c>=.5)+8*(d>=.5)
    ys,xs=np.nonzero((cases!=0)&(cases!=15));links=defaultdict(list)
    table={1:[(3,0)],2:[(0,1)],3:[(3,1)],4:[(1,2)],6:[(0,2)],7:[(3,2)],8:[(2,3)],9:[(2,0)],11:[(2,1)],12:[(1,3)],13:[(1,0)],14:[(0,3)]}
    for y,x in zip(ys,xs):
        tl,tr,br,bl=map(float,(values[y,x],values[y,x+1],values[y+1,x+1],values[y+1,x]))
        case=int(cases[y,x]);inside=(tl+tr+br+bl)/4>=.5
        pairs=table.get(case)
        if case==5:pairs=[(0,1),(2,3)] if inside else [(3,0),(1,2)]
        if case==10:pairs=[(3,0),(1,2)] if inside else [(0,1),(2,3)]
        def edge(i):
            if i==0:px,py=x-.5+(.5-tl)/(tr-tl),y-.5
            elif i==1:px,py=x+.5,y-.5+(.5-tr)/(br-tr)
            elif i==2:px,py=x-.5+(.5-bl)/(br-bl),y+.5
            else:px,py=x-.5,y-.5+(.5-tl)/(bl-tl)
            return round(px,6),round(py,6)
        for i,j in pairs:
            p,q=edge(i),edge(j);links[p].append(q);links[q].append(p)
    assert all(len(n)==2 for n in links.values())
    seen=set();loops=[]
    for start in links:
        if start in seen:continue
        points=[];previous=None;p=start
        while p not in seen:
            seen.add(p);points.append(p);n=links[p]
            q=n[0] if n[0]!=previous else n[1]
            previous,p=p,q
        assert p==start
        if len(points)>2:
            loops.append(cv2.approxPolyDP(np.array(points,np.float32),.08,True).reshape(-1,2).astype(float))
    return loops

def main():
    im=Image.open(SOURCE).convert('RGBA');background=Image.new('RGBA',im.size,'white');background.alpha_composite(im)
    gray=cv2.cvtColor(np.array(background.convert('RGB')),cv2.COLOR_RGB2GRAY)
    ink=(255-gray).astype(float)/255;binary=ink>=.5;height,width=gray.shape
    rows=np.flatnonzero(binary.mean(axis=1)>.98);line_top,line_bottom=int(rows[0]),int(rows[-1])+1
    text=ink.copy();text[line_top:]=0;text_binary=text>=.5
    nonempty=np.flatnonzero(np.any(text>0,axis=1));origin=max(0,int(nonempty[0])-1)
    crop_height=min(height,line_bottom+1)-origin
    loops=trace(text)
    num=lambda n:format(round(float(n),3),'g')
    compound=' '.join('M'+' L'.join(num(x)+','+num(y) for x,y in loop)+'Z' for loop in loops)
    blank=np.flatnonzero(~np.any(text_binary,axis=0));runs=np.split(blank,np.where(np.diff(blank)>1)[0]+1)
    space=max((r for r in runs if len(r) and r[0]>width*.1 and r[-1]<width*.9),key=len)
    count,labels,stats,centroids=cv2.connectedComponentsWithStats(text_binary.astype(np.uint8),8)
    index=min((i for i in range(1,count) if stats[i,4]>20),key=lambda i:stats[i,0])
    ox,oy,ow,oh,_=stats[index].tolist()
    candidates=[p for p in loops if p[:,0].min()<ox+ow and p[:,0].max()>ox and p[:,1].min()<oy+oh and p[:,1].max()>oy and p[:,0].max()<ox+ow+2]
    points=np.concatenate(candidates);left,right=points[:,0].min(),points[:,0].max();top,bottom=points[:,1].min(),points[:,1].max()
    row_count=text_binary.sum(axis=1).astype(float);lo,hi=int(oy+oh*.65),line_top-3
    baseline=int(np.argmin(np.diff(row_count)[lo:hi]))+lo+1
    metrics=dict(Width=float(width),Height=float(crop_height),Baseline=float(baseline-origin),RuleTop=float(line_top-origin),RuleHeight=float(line_bottom-line_top),CapCenter=float((top+bottom)/2-origin),OLeft=float(left),OWidth=float(right-left),OTop=float(top-origin),OBottom=float(bottom-origin),OCounterX=float((left+right)/2),OCounterY=float((top+bottom)/2-origin),OpenEnd=float(space[0]),RhodesStart=float(space[-1]+1))
    desc='<title>Open Rhodes</title><desc>Direct image trace; original glyph positions and spacing preserved.</desc>'
    path=f'<path id="wordmark" fill="currentColor" fill-rule="evenodd" d="{compound}"/>'
    rule=f'<path id="underline" fill="currentColor" d="M0 {line_top}H{width}V{line_bottom}H0Z"/>'
    for name,canvas_h,view_y in [('openrhodes-from-image.svg',height,0),('openrhodes-wordmark.svg',crop_height,origin)]:
        svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{canvas_h}" viewBox="0 {view_y} {width} {canvas_h}">\n{desc}\n{path}\n{rule}\n</svg>\n'
        (ROOT/'artwork'/name).write_text(svg,encoding='utf-8')
    # 按原图的独立连通块分层，每块保留自己的字腔，避免单条 Android 路径超限。
    outlines=[VectorPath(np.vstack([loop,loop[0]]),closed=True) for loop in loops]
    depth=[sum(other.contains_point(loop[0]) for j,other in enumerate(outlines) if j!=i) for i,loop in enumerate(loops)]
    native_paths=[]
    for i,loop in enumerate(loops):
        if depth[i]!=0:continue
        pieces=[loop]+[p for j,p in enumerate(loops) if depth[j]==1 and outlines[i].contains_point(p[0])]
        native_paths.append(' '.join('M'+' L'.join(num(x)+','+num(y) for x,y in part)+'Z' for part in pieces))
    assert all(len(path.encode('utf-8'))<30000 for path in native_paths)
    vector=f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{width}dp" android:height="{crop_height}dp" android:viewportWidth="{width}" android:viewportHeight="{crop_height}">\n  <group android:translateY="{-origin}">\n'
    vector+=''.join(f'    <path android:fillColor="#FFF4E8D1" android:fillType="evenOdd" android:pathData="{path}"/>\n' for path in native_paths)
    vector+='  </group>\n</vector>\n'
    (ROOT/'app/src/main/res/drawable/openrhodes_wordmark.xml').write_text(vector,encoding='utf-8')
    code=['package com.openrhodes.ui','','// 从输入 PNG 整体描摹生成，不能逐字重排。','internal object WordmarkGeometry {']
    code += [f'    const val {k} = {v:.4f}f' for k,v in metrics.items()]+['}']
    (ROOT/'app/src/main/java/com/openrhodes/ui/WordmarkGeometry.kt').write_text('\n'.join(code)+'\n',encoding='utf-8')
    meta=dict(label='Open Rhodes',source='user-provided openrhodes.png',sourceImageSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),originalCanvas=[width,height],crop=[0,origin,width,crop_height],metrics=metrics,contourCount=len(loops),pointCount=sum(len(p) for p in loops),maximumSimplificationErrorPixels=.08,nativePathCount=len(native_paths),longestNativePathBytes=max(len(p.encode('utf-8')) for p in native_paths))
    (ROOT/'artwork/wordmark-image-trace.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    # 使用标准矢量栅格器回渲染，避免 fillPoly 的边界像素扩张影响比较。
    import matplotlib
    matplotlib.use('Agg')
    from matplotlib.patches import PathPatch, Rectangle
    from matplotlib.figure import Figure
    from matplotlib.backends.backend_agg import FigureCanvasAgg
    vertices=[];codes=[]
    candidates=[VectorPath(np.vstack([loop,loop[0]]),closed=True) for loop in loops]
    for i,loop in enumerate(loops):
        depth=sum(path.contains_point(loop[0]) for j,path in enumerate(candidates) if j!=i)
        positive=cv2.contourArea(loop.astype(np.float32),oriented=True)>0
        if positive != (depth%2==0):loop=loop[::-1]
        vertices.extend(loop.tolist()+[loop[0].tolist()])
        codes.extend([VectorPath.MOVETO]+[VectorPath.LINETO]*(len(loop)-1)+[VectorPath.CLOSEPOLY])
    fig=Figure(figsize=(width/100,height/100),dpi=100,facecolor='white')
    ax=fig.add_axes([0,0,1,1]);ax.set_xlim(0,width);ax.set_ylim(height,0);ax.axis('off')
    ax.add_patch(PathPatch(VectorPath(vertices,codes),facecolor='black',edgecolor='none'))
    ax.add_patch(Rectangle((0,line_top),width,line_bottom-line_top,facecolor='black',edgecolor='none'))
    canvas=FigureCanvasAgg(fig);canvas.draw()
    rgba=np.asarray(canvas.buffer_rgba()).copy()
    rendered=255-cv2.cvtColor(rgba[:,:,:3],cv2.COLOR_RGB2GRAY);predicted=rendered>=128
    iou=np.count_nonzero(predicted & binary)/np.count_nonzero(predicted | binary)
    out=ROOT/'app/build/image-trace-review';out.mkdir(parents=True,exist_ok=True)
    Image.fromarray(255-rendered).convert('RGB').save(out/'roundtrip.png')
    overlay=np.full((height,width,3),255,np.uint8);overlay[binary & predicted]=(25,25,25)
    overlay[binary & ~predicted]=(225,65,65);overlay[predicted & ~binary]=(40,160,215)
    Image.fromarray(overlay).save(out/'difference-overlay.png')
    report=dict(silhouetteIoU=float(iou),mismatchedPixels=int(np.count_nonzero(predicted!=binary)),inputInkPixels=int(binary.sum()),contourCount=len(loops),pointCount=meta['pointCount'])
    (out/'verification.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    assert iou>.995,report
    print(json.dumps(dict(metrics=metrics,verification=report),ensure_ascii=False))

if __name__=='__main__':main()
