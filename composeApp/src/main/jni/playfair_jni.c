#include <jni.h>
#include <string.h>
#include "playfair.h"

JNIEXPORT jbyteArray JNICALL
Java_com_teachmint_sharex_airplay_PlayfairNative_nativeDecrypt(
    JNIEnv *env, jobject thiz,
    jbyteArray message3, jbyteArray cipherText)
{
    jbyte *m3  = (*env)->GetByteArrayElements(env, message3, NULL);
    jbyte *ct  = (*env)->GetByteArrayElements(env, cipherText, NULL);

    unsigned char keyOut[16];
    memset(keyOut, 0, 16);

    playfair_decrypt((unsigned char *)m3, (unsigned char *)ct, keyOut);

    (*env)->ReleaseByteArrayElements(env, message3, m3, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, cipherText, ct, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, 16);
    (*env)->SetByteArrayRegion(env, result, 0, 16, (jbyte *)keyOut);
    return result;
}
