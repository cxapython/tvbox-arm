#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "TVScript.h"

typedef struct { TVScript *script; jobject host; jmethodID method; JNIEnv *env; } Bridge;
// UTF-8 passes as byte arrays, avoiding JNI modified-UTF8 corruption of emoji / supplementary characters.
static jbyteArray bytes(JNIEnv *e,const char *s){if(!s)return NULL;size_t n=strlen(s);jbyteArray b=(*e)->NewByteArray(e,(jsize)n);if(b)(*e)->SetByteArrayRegion(e,b,0,(jsize)n,(const jbyte*)s);return b;}
static char *string(JNIEnv *e,jbyteArray b){if(!b)return NULL;jsize n=(*e)->GetArrayLength(e,b);char *s=malloc((size_t)n+1);if(!s)return NULL;(*e)->GetByteArrayRegion(e,b,0,n,(jbyte*)s);s[n]=0;return s;}
static char *host(void *opaque,const char *op,const char *arg){
    Bridge *b=opaque;JNIEnv *e=b->env;
    jbyteArray a=bytes(e,op),c=bytes(e,arg),result=(jbyteArray)(*e)->CallObjectMethod(e,b->host,b->method,a,c);
    char *out=NULL;
    if((*e)->ExceptionCheck(e)){(*e)->ExceptionClear(e);out=strdup("\x01" "Android script host failed");}else out=string(e,result);
    if(a)(*e)->DeleteLocalRef(e,a);if(c)(*e)->DeleteLocalRef(e,c);if(result)(*e)->DeleteLocalRef(e,result);return out;
}
JNIEXPORT jlong JNICALL Java_com_yingxia_tv_runtime_ScriptRuntime_create(JNIEnv *e,jobject self){
    Bridge *b=calloc(1,sizeof(Bridge));if(!b)return 0;b->env=e;b->host=(*e)->NewGlobalRef(e,self);
    jclass cls=(*e)->GetObjectClass(e,self);b->method=(*e)->GetMethodID(e,cls,"respond","([B[B)[B");(*e)->DeleteLocalRef(e,cls);
    b->script=tv_script_create(b,host,48*1024*1024,1024*1024);
    if(!b->script){(*e)->DeleteGlobalRef(e,b->host);free(b);return 0;}return (jlong)(intptr_t)b;
}
JNIEXPORT jbyteArray JNICALL Java_com_yingxia_tv_runtime_ScriptRuntime_module(JNIEnv *e,jobject self,jlong handle,jbyteArray name,jbyteArray source){
    Bridge *b=(Bridge*)(intptr_t)handle;b->env=e;char *n=string(e,name),*s=string(e,source);char *error=tv_script_module(b->script,n,s,35);free(n);free(s);jbyteArray out=bytes(e,error);free(error);return out;
}
JNIEXPORT jbyteArray JNICALL Java_com_yingxia_tv_runtime_ScriptRuntime_evaluate(JNIEnv *e,jobject self,jlong handle,jbyteArray source,jint seconds){
    Bridge *b=(Bridge*)(intptr_t)handle;b->env=e;char *s=string(e,source);char *answer=tv_script_call(b->script,s,seconds);free(s);jbyteArray out=bytes(e,answer);free(answer);return out;
}
JNIEXPORT void JNICALL Java_com_yingxia_tv_runtime_ScriptRuntime_dispose(JNIEnv *e,jobject self,jlong handle){Bridge *b=(Bridge*)(intptr_t)handle;if(!b)return;b->env=e;tv_script_free(b->script);(*e)->DeleteGlobalRef(e,b->host);free(b);}
