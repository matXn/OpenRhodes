#pragma once
#include <stdint.h>

// Scan observations are transient: never change saved priority or credentials.
struct WifiAutoConnectPlan {
  uint16_t visibleMask=0;
  uint8_t channels[8]={};
  int32_t strengths[8]={};
  void seen(int index,uint8_t channel,int32_t rssi) {
    if(index<0||index>=8)return;
    if(!(visibleMask&(1u<<index))||rssi>strengths[index]){
      channels[index]=channel;strengths[index]=rssi;
    }
    visibleMask|=1u<<index;
  }
  int next(int count,uint16_t tried) const {
    for(int pass=0;pass<2;pass++)
      for(int i=0;i<count&&i<8;i++)
        if(!(tried&(1u<<i))&&bool(visibleMask&(1u<<i))==(pass==0))return i;
    return -1;
  }
};
