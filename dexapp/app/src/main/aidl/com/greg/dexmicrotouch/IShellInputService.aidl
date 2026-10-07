package com.greg.dexmicrotouch;

interface IShellInputService {
    void destroy() = 16777114;
    int getUid() = 1;
    boolean inject(int displayId, int action, float x, float y) = 2;
    boolean resetTouch(int displayId, float x, float y) = 3;
    boolean key(int displayId, int keyCode) = 4;
}
