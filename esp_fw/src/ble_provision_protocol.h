#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

// OpenRhodes v1, inside Espressif Security 1 (not an unencrypted GATT write).
struct BleProvisionRequest {
  uint8_t operation=0, offset=0;
  char ssid[33]={}, password[65]={};
  bool decode(const uint8_t* data,size_t size) {
    *this={};
    if(!data||size<1||size>101)return false;
    operation=data[0];
    if(operation==1||operation==2||operation==5)return size==1;
    if(operation==3){if(size!=2)return false;offset=data[1];return true;}
    if(operation==6) {
      if(size<3||data[1]<1||data[1]>32||size!=2u+data[1]||memchr(data+2,0,data[1]))return false;
      memcpy(ssid,data+2,data[1]);return true;
    }
    if(operation!=4||size<3)return false;
    size_t s=data[1],p=data[2];
    if(s<1||s>32||p>64||(p>0&&p<8)||size!=3+s+p)return false;
    if(memchr(data+3,0,s+p))return false;
    if(p==64)for(size_t i=3+s;i<size;i++) {
      uint8_t c=data[i];if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F')))return false;
    }
    memcpy(ssid,data+3,s);memcpy(password,data+3+s,p);
    return true;
  }
};
