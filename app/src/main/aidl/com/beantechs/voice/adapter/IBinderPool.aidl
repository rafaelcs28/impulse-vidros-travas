// IBinderPool.aidl
package com.beantechs.voice.adapter;

// Declare any non-default types here with import statements

interface IBinderPool {
    IBinder queryBinder(int binderCode);
}