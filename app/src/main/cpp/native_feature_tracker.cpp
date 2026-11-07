#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace {
struct P { int x; int y; int score; };
int at(const uint8_t* p,int x,int y,int rs,int ps){ return p[y*rs+x*ps]; }

int fast(const uint8_t* p,int x,int y,int rs,int ps) {
    static constexpr int ox[16]={0,1,2,3,3,3,2,1,0,-1,-2,-3,-3,-3,-2,-1};
    static constexpr int oy[16]={-3,-3,-2,-1,0,1,2,3,3,3,2,1,0,-1,-2,-3};
    const int c=at(p,x,y,rs,ps);
    int bright=0,dark=0;
    for(int i=0;i<24;i++){
        int d=at(p,x+ox[i&15],y+oy[i&15],rs,ps)-c;
        bright=d>22?bright+1:0; dark=d< -22?dark+1:0;
        if(bright>=9||dark>=9) return 1+std::abs(d);
    }
    return 0;
}
std::vector<P> detect(const uint8_t* p,int w,int h,int rs,int ps){
    std::vector<P> all, chosen;
    for(int y=4;y<h-4;y+=2) for(int x=4;x<w-4;x+=2){
        int s=fast(p,x,y,rs,ps); if(s) all.push_back({x,y,s});
    }
    std::sort(all.begin(),all.end(),[](const P&a,const P&b){return a.score>b.score;});
    for(const P&q:all){
        bool ok=true;
        for(const P&r:chosen){int dx=q.x-r.x,dy=q.y-r.y;if(dx*dx+dy*dy<64){ok=false;break;}}
        if(ok) chosen.push_back(q);
        if(chosen.size()>=140) break;
    }
    return chosen;
}
int cost(const uint8_t*a,const uint8_t*b,int ax,int ay,int bx,int by,int rs,int ps){
    int sum=0;
    for(int y=-2;y<=2;y++)for(int x=-2;x<=2;x++)
        sum+=std::abs(at(a,ax+x,ay+y,rs,ps)-at(b,bx+x,by+y,rs,ps));
    return sum/25;
}
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_trackstudio_artracker_NativeFeatureTracker_processFrame(
 JNIEnv* env,jobject,jbyteArray array,jint w,jint h,jint rs,jint ps){
    if(!array||w<16||h<16||rs<w||ps<1)return env->NewLongArray(4);
    jsize n=env->GetArrayLength(array);
    if(static_cast<jlong>(h-1)*rs+static_cast<jlong>(w-1)*ps+1>n)return env->NewLongArray(4);
    jbyte* raw=env->GetByteArrayElements(array,nullptr); if(!raw)return env->NewLongArray(4);
    const auto* cur=reinterpret_cast<const uint8_t*>(raw);
    auto features=detect(cur,w,h,rs,ps);
    static std::vector<uint8_t> prev;
    static std::vector<P> prev_points;
    static int pw=0,ph=0,prs=0,pps=0;
    int tracked=0; double sx=0,sy=0;
    if(pw==w&&ph==h&&prs==rs&&pps==ps&&!prev.empty()){
        for(const P&q:prev_points){
            if(q.x<3||q.y<3||q.x>=w-3||q.y>=h-3)continue;
            int best=999,bx=q.x,by=q.y;
            for(int y=std::max(3,q.y-7);y<=std::min(h-4,q.y+7);y++)
            for(int x=std::max(3,q.x-7);x<=std::min(w-4,q.x+7);x++){
                int c=cost(prev.data(),cur,q.x,q.y,x,y,rs,ps);
                if(c<best){best=c;bx=x;by=y;}
            }
            if(best<30){tracked++;sx+=bx-q.x;sy+=by-q.y;}
        }
    }
    jlong outv[4]={static_cast<jlong>(features.size()),tracked,
        tracked?static_cast<jlong>(std::llround(sx*1000/tracked)):0,
        tracked?static_cast<jlong>(std::llround(sy*1000/tracked)):0};
    prev.assign(cur,cur+static_cast<size_t>(rs)*h);
    prev_points=std::move(features); pw=w;ph=h;prs=rs;pps=ps;
    env->ReleaseByteArrayElements(array,raw,JNI_ABORT);
    jlongArray out=env->NewLongArray(4); if(out)env->SetLongArrayRegion(out,0,4,outv);
    return out;
}
