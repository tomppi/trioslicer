#include "GHOST_SystemAndroid.hh"
#include "android_native_app_glue.h"
#include "GHOST_EventCursor.hh"
#include "GHOST_WindowAndroid.hh"
#include "GHOST_WindowManager.hh"
#include "GHOST_EventButton.hh"
#include "GHOST_TimerManager.hh"
#include "GHOST_EventKey.hh"
#include "GHOST_EventWheel.hh"
#include "OBLButtonID.h"
#include <android/input.h>
#include <CLG_log.h>
#include <zconf.h>
#include <android/native_window_jni.h>

static CLG_LogRef LOG = {"Ghost.wm"};

GHOST_TKey processSpecialKey(short vKey, short /*scanCode*/) {
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 26");
    GHOST_TKey key = GHOST_kKeySpace;
    if (vKey == 0xFF) {
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 27");
        /* 0xFF is not a valid virtual key code. */
        return key;
    }
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 28");
//    char ch = char(MapVirtualKeyA(vKey, MAPVK_VK_TO_CHAR));
//    switch (ch) {
//        case u'\"':
//        case u'\'':
//            key = GHOST_kKeyQuote;
//            break;
//        case u'.':
//            key = GHOST_kKeyNumpadPeriod;
//            break;
//        case u'/':
//            key = GHOST_kKeySlash;
//            break;
//        case u'`':
//        case u'²':
//            key = GHOST_kKeyAccentGrave;
//            break;
//        default:
//            if (vKey == VK_OEM_7) {
//                key = GHOST_kKeyQuote;
//            }
//            else if (vKey == VK_OEM_8) {
//                if (PRIMARYLANGID(m_langId) == LANG_FRENCH) {
//                    /* OEM key; used purely for shortcuts. */
//                    key = GHOST_kKeyF13;
//                }
//            }
//            break;
//    }

    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 29");
    return key;
}

GHOST_TKey convertKey(short vKey, short scanCode, short extend) {
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 30 %d %d %d", vKey, scanCode, extend);
    GHOST_TKey key=GHOST_kKeyBackSpace;

    if ((vKey >= AKEYCODE_0) && (vKey <= AKEYCODE_9)) {
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 31");
        /* VK_0 thru VK_9 are the same as ASCII '0' thru '9' (0x30 - 0x39). */
        key = (GHOST_TKey) (vKey - AKEYCODE_0 + GHOST_kKey0);
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 32");
    } else if ((vKey >= AKEYCODE_A) && (vKey <= AKEYCODE_Z)) {
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 33");
        /* VK_A thru VK_Z are the same as ASCII 'A' thru 'Z' (0x41 - 0x5A). */
        key = (GHOST_TKey) (vKey - AKEYCODE_A + GHOST_kKeyA);
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 34");
    } else {
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35");
        switch (vKey) {
            case AKEYCODE_ENTER:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 1");
                key = (extend) ? GHOST_kKeyNumpadEnter : GHOST_kKeyEnter;
                break;
            case AKEYCODE_DEL:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 2");
                key = GHOST_kKeyBackSpace;
                break;
            case AKEYCODE_TAB:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 3");
                key = GHOST_kKeyTab;
                break;
            case AKEYCODE_ESCAPE:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 4");
                key = GHOST_kKeySpace;
                break;
            case AKEYCODE_SPACE:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 5");
                key = GHOST_kKeySpace;
                break;
            case AKEYCODE_INSERT:
            case AKEYCODE_NUMPAD_0:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 6");
                key = (extend) ? GHOST_kKeyInsert : GHOST_kKeyNumpad0;
                break;
            case AKEYCODE_MOVE_END:
            case AKEYCODE_NUMPAD_1:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 7");
                key = (extend) ? GHOST_kKeyEnd : GHOST_kKeyNumpad1;
                break;
            case AKEYCODE_DPAD_DOWN:
            case AKEYCODE_NUMPAD_2:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 8");
                key = (extend) ? GHOST_kKeyDownArrow : GHOST_kKeyNumpad2;
                break;
            case AKEYCODE_PAGE_DOWN:
            case AKEYCODE_NUMPAD_3:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 9");
                key = (extend) ? GHOST_kKeyDownPage : GHOST_kKeyNumpad3;
                break;
            case AKEYCODE_DPAD_LEFT:
            case AKEYCODE_NUMPAD_4:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 10");
                key = (extend) ? GHOST_kKeyLeftArrow : GHOST_kKeyNumpad4;
                break;
            case AKEYCODE_CLEAR:
            case AKEYCODE_NUMPAD_5:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 11");
                key = (extend) ? GHOST_kKeyClear : GHOST_kKeyNumpad5;
                break;
            case AKEYCODE_DPAD_RIGHT:
            case AKEYCODE_NUMPAD_6:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 12");
                key = (extend) ? GHOST_kKeyRightArrow : GHOST_kKeyNumpad6;
                break;
            case AKEYCODE_MOVE_HOME:
            case AKEYCODE_NUMPAD_7:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 13");
                key = (extend) ? GHOST_kKeyHome : GHOST_kKeyNumpad7;
                break;
            case AKEYCODE_DPAD_UP:
            case AKEYCODE_NUMPAD_8:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 14");
                key = (extend) ? GHOST_kKeyUpArrow : GHOST_kKeyNumpad8;
                break;
            case AKEYCODE_PAGE_UP:
            case AKEYCODE_NUMPAD_9:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 15");
                key = (extend) ? GHOST_kKeyUpPage : GHOST_kKeyNumpad9;
                break;
            case AKEYCODE_FORWARD_DEL:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 16");
                key = GHOST_kKeySpace;
                break;
//
//            case VK_SNAPSHOT:
//                key = GHOST_kKeyPrintScreen;
//                break;
            case AKEYCODE_BREAK:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 17");
                key = GHOST_kKeyPause;
                break;
            case AKEYCODE_NUMPAD_MULTIPLY:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 18");
                key = GHOST_kKeyNumpadAsterisk;
                break;
            case AKEYCODE_NUMPAD_SUBTRACT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 19");
                key = GHOST_kKeyNumpadMinus;
                break;
            case AKEYCODE_NUMPAD_DIVIDE:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 20");
                key = GHOST_kKeyNumpadSlash;
                break;
            case AKEYCODE_NUMPAD_ADD:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 21");
                key = GHOST_kKeyNumpadPlus;
                break;

            case AKEYCODE_SEMICOLON:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 22");
                key = GHOST_kKeySemicolon;
                break;
            case AKEYCODE_EQUALS:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 23");
                key = GHOST_kKeyEqual;
                break;
            case AKEYCODE_COMMA:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 24");
                key = GHOST_kKeyComma;
                break;
            case AKEYCODE_MINUS:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 25");
                key = GHOST_kKeyMinus;
                break;
            case AKEYCODE_PERIOD:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 26");
                key = GHOST_kKeyPeriod;
                break;
            case AKEYCODE_SLASH:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 27");
                key = GHOST_kKeySlash;
                break;
//            case VK_BACK_QUOTE:
//                key = GHOST_kKeyAccentGrave;
//                break;
            case AKEYCODE_LEFT_BRACKET:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 28");
                key = GHOST_kKeyLeftBracket;
                break;
            case AKEYCODE_BACKSLASH:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 29");
                key = GHOST_kKeyBackslash;
                break;
            case AKEYCODE_RIGHT_BRACKET:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 30");
                key = GHOST_kKeyRightBracket;
                break;
            case AKEYCODE_SOFT_SLEEP:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 31");
                key = GHOST_kKeyGrLess;
                break;

            case AKEYCODE_SHIFT_LEFT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 32");
                key = GHOST_kKeyLeftShift;
                break;
            case AKEYCODE_SHIFT_RIGHT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 33");
                key = GHOST_kKeyRightShift;
                break;
            case AKEYCODE_CTRL_LEFT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 34");
                key = GHOST_kKeyLeftControl;
                break;
            case AKEYCODE_CTRL_RIGHT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 35");
                key = GHOST_kKeyRightControl;
                break;
            case AKEYCODE_ALT_LEFT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 36");
                key = GHOST_kKeyLeftAlt;
                break;
            case AKEYCODE_ALT_RIGHT:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 37");
                key = GHOST_kKeyRightAlt;
                break;
//            case VK_LWIN:
//                key = GHOST_kKeyLeftOS;
//                break;
//            case VK_RWIN:
//                key = GHOST_kKeyRightOS;
//                break;
//            case VK_APPS:
//                key = GHOST_kKeyApp;
//                break;
            case AKEYCODE_NUM_LOCK:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 38");
                key = GHOST_kKeyNumLock;
                break;
            case AKEYCODE_SCROLL_LOCK:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 39");
                key = GHOST_kKeySpace;
                break;
            case AKEYCODE_CAPS_LOCK:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 40");
                key = GHOST_kKeyCapsLock;
                break;
//            case VK_MEDIA_PLAY_PAUSE:
//                key = GHOST_kKeyMediaPlay;
//                break;
//            case VK_MEDIA_STOP:
//                key = GHOST_kKeyMediaStop;
//                break;
//            case VK_MEDIA_PREV_TRACK:
//                key = GHOST_kKeyMediaFirst;
//                break;
//            case VK_MEDIA_NEXT_TRACK:
//                key = GHOST_kKeyMediaLast;
//                break;
//            case VK_OEM_7:
//            case VK_OEM_8:
            default:
            // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 41");
                key = processSpecialKey(vKey, scanCode);
                // CLOG_ERROR(&LOG, "交互showHidenKeyboard 35 42");
                break;
        }
    }
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 36");

    return key;
}

GHOST_TKey hardKey(AInputEvent *event) {
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 37");
    int32_t keyflags = AKeyEvent_getFlags(event);                 //  key flags
    int32_t keycode = AKeyEvent_getKeyCode(
            event);                //  key code AKEYCODE_0  AKEYCODE_NUMPAD_3 键盘符号
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 38");

    return convertKey(keycode, AKeyEvent_getScanCode(event), 0);
}

int ToUnicodeEx(int32_t vKey, int32_t keyMetaState, wchar_t *utf16) {
    if ((vKey >= AKEYCODE_0) && (vKey <= AKEYCODE_9)) {
        // CLOG_ERROR(&LOG, "交互ToUnicodeEx 1");
        /* VK_0 thru VK_9 are the same as ASCII '0' thru '9' (0x30 - 0x39). */
        utf16[0] = (GHOST_TKey) (vKey - AKEYCODE_0 + GHOST_kKey0);
        // CLOG_ERROR(&LOG, "交互ToUnicodeEx 2");
    } else if ((vKey >= AKEYCODE_A) && (vKey <= AKEYCODE_Z)) {
        // CLOG_ERROR(&LOG, "交互ToUnicodeEx 3 %d %d", vKey, keyMetaState);
        /* VK_A thru VK_Z are the same as ASCII 'A' thru 'Z' (0x41 - 0x5A). */
        utf16[0] = (GHOST_TKey) (vKey - AKEYCODE_A + GHOST_kKeyA) + 32;
        // CLOG_ERROR(&LOG, "交互ToUnicodeEx 4");
        if ((keyMetaState & AMETA_SHIFT_ON) != 0) {
            utf16[0] = utf16[0] - 32;
        }
    } else if (vKey == AKEYCODE_PERIOD) {
        utf16[0] = GHOST_kKeyPeriod;
    } else if (vKey == AKEYCODE_MINUS) {
        utf16[0] = GHOST_kKeyMinus;
        if ((keyMetaState & AMETA_SHIFT_ON) != 0) {
            utf16[0] = '_';
        }
    } else if (vKey == AKEYCODE_PLUS) {
        utf16[0] = GHOST_kKeyPlus;
    }
    return 1;
}
/** Error occurs when required parameter is missing. */
#define UTF_ERROR_NULL_IN (1 << 0)
/** Error if character is in illegal UTF range. */
#define UTF_ERROR_ILLCHAR (1 << 1)
/** Passed size is to small. It gives legal string with character missing at the end. */
#define UTF_ERROR_SMALL (1 << 2)
/** Error if sequence is broken and doesn't finish. */
#define UTF_ERROR_ILLSEQ (1 << 3)

static int conv_utf_16_to_8(const wchar_t *in16, char *out8, size_t size8) {
    char *out8end = out8 + size8;
    wchar_t u = 0;
    int err = 0;
    if (!size8 || !in16 || !out8) {
        return UTF_ERROR_NULL_IN;
    }
    out8end--;

    for (; out8 < out8end && (u = *in16); in16++, out8++) {
        if (u < 0x0080) {
            *out8 = u;
        } else if (u < 0x0800) {
            if (out8 + 1 >= out8end) {
                break;
            }
            *out8++ = (0x3 << 6) | (0x1F & (u >> 6));
            *out8 = (0x1 << 7) | (0x3F & (u));
        } else if (u < 0xD800 || u >= 0xE000) {
            if (out8 + 2 >= out8end) {
                break;
            }
            *out8++ = (0x7 << 5) | (0xF & (u >> 12));
            *out8++ = (0x1 << 7) | (0x3F & (u >> 6));
            *out8 = (0x1 << 7) | (0x3F & (u));
        } else if (u < 0xDC00) {
            wchar_t u2 = *++in16;

            if (!u2) {
                break;
            }
            if (u2 >= 0xDC00 && u2 < 0xE000) {
                if (out8 + 3 >= out8end) {
                    break;
                }
                unsigned int uc = 0x10000 + (u2 - 0xDC00) + ((u - 0xD800) << 10);

                *out8++ = (0xF << 4) | (0x7 & (uc >> 18));
                *out8++ = (0x1 << 7) | (0x3F & (uc >> 12));
                *out8++ = (0x1 << 7) | (0x3F & (uc >> 6));
                *out8 = (0x1 << 7) | (0x3F & (uc));
            } else {
                out8--;
                err |= UTF_ERROR_ILLCHAR;
            }
        } else if (u < 0xE000) {
            out8--;
            err |= UTF_ERROR_ILLCHAR;
        }
    }

    *out8 = *out8end = 0;

    if (*in16) {
        err |= UTF_ERROR_SMALL;
    }

    return err;
}

//  触摸笔（蓝牙触摸笔）
void processStylusEvent(struct android_app *app, AInputEvent *event){
    float pressure=AMotionEvent_getPressure(event,0);
    float size=AMotionEvent_getSize(event,0);
}

//  鼠标事件
GHOST_Event *processMouseEvent(struct android_app *app, AInputEvent *event) {
    // CLOG_ERROR(&LOG, "交互processMouseEvent 1");
    GHOST_SystemAndroid *system = (GHOST_SystemAndroid *) GHOST_ISystem::getSystem();
    // CLOG_ERROR(&LOG, "交互processMouseEvent 2");

    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) system->getWindowManager()->getActiveWindow();
    uint32_t width, height;
    system->getMainDisplayDimensions(width, height);
    // CLOG_ERROR(&LOG, "交互processMouseEvent 3");

    int32_t buttonState = AMotionEvent_getButtonState(event);
    int32_t motionaction = AMotionEvent_getAction(event);
    // CLOG_ERROR(&LOG, "交互processMouseEvent 4");

    float msgPosX = AMotionEvent_getX(event, 0);    //  x坐标
    float msgPosY = AMotionEvent_getY(event, 0);    //  y坐标
    // CLOG_ERROR(&LOG, "交互-----------------processMouseEvent 开始5 %d %d %f %f------------",buttonState, motionaction, msgPosX, msgPosY);

    system->m_x = msgPosX;
    system->m_y = msgPosY;
    uint64_t currentTime = system->getMilliSeconds();
    GHOST_TabletData td;
    // CLOG_ERROR(&LOG, "交互processMouseEvent 6");

    if (buttonState & AMOTION_EVENT_BUTTON_PRIMARY) {
        system->m_lastButton = GHOST_kButtonMaskLeft;
        // CLOG_ERROR(&LOG, "交互processMouseEvent 7 左键");
        //  鼠标左键
        if (motionaction == AMOTION_EVENT_ACTION_DOWN) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 8 左键按下 1");
            //  按下
            system->pushEvent(
                    new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                          msgPosY, td));
            //  按下
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonDown,
                                          window,
                                          GHOST_kButtonMaskLeft,
                                          td);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 9 左键按下 2");
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 10 左键按下 3");
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_UP) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 左键弹起11");
            //  弹起
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonUp,
                                          window,
                                          GHOST_kButtonMaskLeft,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 左键弹起12");
            return eventButton;
        }
        if ((motionaction == AMOTION_EVENT_ACTION_MOVE) ||
            (motionaction == AMOTION_EVENT_ACTION_HOVER_MOVE)) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 左键移动13");
            //  移动
            GHOST_Event *eventButton =
                    new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                          msgPosY, td);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 左键移动14");
            system->pushEvent(eventButton);
            return eventButton;
        }
    } else if (buttonState & AMOTION_EVENT_BUTTON_SECONDARY) {
        system->m_lastButton = GHOST_kButtonMaskRight;
        // CLOG_ERROR(&LOG, "交互processMouseEvent 右键15");
        //  鼠标右键
        if (motionaction == AMOTION_EVENT_ACTION_DOWN) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 右键按下16");
            //  按下
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonDown,
                                          window,
                                          GHOST_kButtonMaskRight,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 右键按下17");
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_UP) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 右键弹起18");
            //  弹起
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonUp,
                                          window,
                                          GHOST_kButtonMaskRight,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 右键弹起19");
            return eventButton;
        }
        if ((motionaction == AMOTION_EVENT_ACTION_MOVE) ||
            (motionaction == AMOTION_EVENT_ACTION_HOVER_MOVE)) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 右键移动20");
            //  移动
            GHOST_Event *eventButton =
                    new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                          msgPosY, td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 右键移动21");
            return eventButton;
        }
    } else if (buttonState & AMOTION_EVENT_BUTTON_TERTIARY) {
        system->m_lastButton = GHOST_kButtonMaskMiddle;
        // CLOG_ERROR(&LOG, "交互processMouseEvent 中键22");
        //  鼠标中建
        if (motionaction == AMOTION_EVENT_ACTION_DOWN) {
            //  按下
            // CLOG_ERROR(&LOG, "交互processMouseEvent 中键按下22 1");
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonDown,
                                          window,
                                          GHOST_kButtonMaskMiddle,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 中键按下23");
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_UP) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 中键弹起24");
            //  弹起
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonUp,
                                          window,
                                          GHOST_kButtonMaskMiddle,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 中键弹起25");
            return eventButton;
        }
        if ((motionaction == AMOTION_EVENT_ACTION_MOVE) ||
            (motionaction == AMOTION_EVENT_ACTION_HOVER_MOVE)) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 中键移动26");
            //  移动
            GHOST_Event *eventButton =
                    new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                          msgPosY, td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 中键移动27");
            return eventButton;
        }
    } else if (buttonState & AMOTION_EVENT_BUTTON_FORWARD) {
        system->m_lastButton = GHOST_kButtonMaskButton4;
        // CLOG_ERROR(&LOG, "交互processMouseEvent 前键28");
        //  鼠标前键
        if (motionaction == AMOTION_EVENT_ACTION_DOWN) {
            //  按下
            // CLOG_ERROR(&LOG, "交互processMouseEvent 前键按下281");
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonDown,
                                          window,
                                          GHOST_kButtonMaskButton4,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 前键按下29");
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_UP) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 前键弹起30");
            //  弹起
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonUp,
                                          window,
                                          GHOST_kButtonMaskButton4,
                                          td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 前键弹起31");
            return eventButton;
        }
        if ((motionaction == AMOTION_EVENT_ACTION_MOVE) ||
            (motionaction == AMOTION_EVENT_ACTION_HOVER_MOVE)) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 前键移动32");
            //  移动
            GHOST_Event *eventButton =
                    new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                          msgPosY, td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 前键移动33");
            return eventButton;
        }
    } else {
        // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键34");
        //  没有按下鼠标
        if (motionaction == AMOTION_EVENT_ACTION_HOVER_ENTER) {
            //  进入
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键进入35");
//            GHOST_EventCursor *eventButton =
//                    new GHOST_EventCursor(currentTime,
//                                          GHOST_TEventType::GHOST_kEventCursorMove,
//                                          window,
//                                          msgPosX,
//                                          msgPosY,
//                                          td);
//            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键进入36");
//            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_HOVER_MOVE) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键移动37");
            //  移动
            GHOST_Event *eventButton =
                    new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                          msgPosY, td);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键移动38");
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_UP) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键弹起39");
            //  弹起
//            int lastButton=system->m_lastButton;
//            GHOST_EventButton *eventButton =
//                    new GHOST_EventButton(currentTime,
//                                          GHOST_TEventType::GHOST_kEventButtonUp,
//                                          window,
//                                          lastButton>=0?GHOST_TButton(lastButton):GHOST_kButtonMaskNone,
//                                          td);
//            system->pushEvent(eventButton);
//            system->m_lastButton=-1;
//            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键弹起40 %d",lastButton);
//            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_SCROLL) {
            //  滚动
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键滚动41");
            float voffset = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_VSCROLL, 0);
            float hoffset = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_HSCROLL, 0);
            int32_t z = voffset;
            GHOST_EventWheel *eventButton =
                    new GHOST_EventWheel(currentTime,
                                         window,
                                         z);
            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键滚动42");
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_UP) {
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键滚动43");
            int lastButton = system->m_lastButton;
            GHOST_EventButton *eventButton =
                    new GHOST_EventButton(currentTime,
                                          GHOST_TEventType::GHOST_kEventButtonUp,
                                          window,
                                          lastButton >= 0 ? GHOST_TButton(lastButton)
                                                          : GHOST_kButtonMaskNone,
                                          td);
            system->pushEvent(eventButton);
            system->m_lastButton = -1;
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键弹起44 %d", lastButton);
            return eventButton;
        }
        if (motionaction == AMOTION_EVENT_ACTION_HOVER_EXIT) {
//            //  退出
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键退出45");
//            GHOST_EventCursor *eventButton =
//                    new GHOST_EventCursor(currentTime,
//                                          GHOST_TEventType::GHOST_kEventCursorMove,
//                                          window,
//                                          msgPosX,
//                                          msgPosY,
//                                          td);
//            system->pushEvent(eventButton);
            // CLOG_ERROR(&LOG, "交互processMouseEvent 没有按键退出46");
//            return eventButton;
        }
        return nullptr;
    }
    // CLOG_ERROR(&LOG, "交互-------------------processMouseEvent 35结束--------------------");
    return nullptr;
}

int GetUnicodeChar(struct android_app *app, int eventType, int keyCode, int metaState) {
    JavaVM *javaVM = app->activity->vm;
    JNIEnv *jniEnv = app->activity->env;

    JavaVMAttachArgs attachArgs;
    attachArgs.version = JNI_VERSION_1_6;
    attachArgs.name = "NativeThread";
    attachArgs.group = NULL;

    jint result = javaVM->AttachCurrentThread(&jniEnv, &attachArgs);
    if (result == JNI_ERR) {
        return 0;
    }

    jclass class_key_event = jniEnv->FindClass("android/view/KeyEvent");
    int unicodeKey;

    if (metaState == 0) {
        jmethodID method_get_unicode_char = jniEnv->GetMethodID(class_key_event, "getUnicodeChar",
                                                                "()I");
        jmethodID eventConstructor = jniEnv->GetMethodID(class_key_event, "<init>", "(II)V");
        jobject eventObj = jniEnv->NewObject(class_key_event, eventConstructor, eventType,
                                             keyCode);

        unicodeKey = jniEnv->CallIntMethod(eventObj, method_get_unicode_char);
    } else {
        jmethodID method_get_unicode_char = jniEnv->GetMethodID(class_key_event, "getUnicodeChar",
                                                                "(I)I");
        jmethodID eventConstructor = jniEnv->GetMethodID(class_key_event, "<init>", "(II)V");
        jobject eventObj = jniEnv->NewObject(class_key_event, eventConstructor, eventType,
                                             keyCode);

        unicodeKey = jniEnv->CallIntMethod(eventObj, method_get_unicode_char, metaState);
    }

    javaVM->DetachCurrentThread();

    // CLOG_ERROR(&LOG, "交互Unicode key is: %d", unicodeKey);
    return unicodeKey;
}

//  输入法键盘点击事件
GHOST_EventKey *processKeyEvent(struct android_app *app, AInputEvent *event) {
//     CLOG_ERROR(&LOG, "交互showHidenKeyboard 39");
    int32_t keyaction = AKeyEvent_getAction(event);               //  key event
    int32_t keyflags = AKeyEvent_getFlags(event);                 //  key flags
    int32_t keycode = AKeyEvent_getKeyCode(event);                //  key code AKEYCODE_0  AKEYCODE_NUMPAD_3 键盘符号
    if ((keyaction==AKEY_EVENT_ACTION_DOWN)||
    (keyaction==AKEY_EVENT_ACTION_UP)){
        if (keycode==AKEYCODE_VOLUME_UP){
//            CLOG_ERROR(&LOG, "交互showHidenKeyboard 40");
            //  处理声音+
            return nullptr;
        }else if (keycode==AKEYCODE_VOLUME_DOWN){
//            CLOG_ERROR(&LOG, "交互showHidenKeyboard 41");
            //  处理声音-
            return nullptr;
        }else if (keycode==AKEYCODE_POWER){
//            CLOG_ERROR(&LOG, "交互showHidenKeyboard 42");
            //  处理电源键
            return nullptr;
        }
    }
//    CLOG_ERROR(&LOG, "交互showHidenKeyboard 43");

    GHOST_SystemAndroid *system = (GHOST_SystemAndroid *) GHOST_ISystem::getSystem();
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) system->getWindowManager()->getActiveWindow();
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 40");

    bool key_down = true;
    bool is_repeat = false;
    bool is_repeated_modifier = false;
    GHOST_TKey key = hardKey(event);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 41");
    int inputEventType = AInputEvent_getType(event);
    int32_t keyMetaState = AKeyEvent_getMetaState(event);
    // 判断 Shift 是否按下
    bool isShiftPressed = (keyMetaState & AMETA_SHIFT_ON) != 0;
    // 判断 Ctrl 是否按下（需注意 Android 原生对 Ctrl 的支持）
    bool isCtrlPressed = (keyMetaState & AMETA_CTRL_ON) != 0;
    // 判断 Alt 是否按下
    bool isAltPressed = (keyMetaState & AMETA_ALT_ON) != 0;
    system->isShiftPressed = isShiftPressed;
    system->isCtrlPressed = isCtrlPressed;
    system->isAltPressed = isAltPressed;
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 42");
    key_down = keyaction == AKEY_EVENT_ACTION_DOWN;
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 43");

    char utf8_char[6] = {0};

    wchar_t utf16[3] = {0};
    int r = GetUnicodeChar(app, inputEventType, keycode, keyMetaState);
    utf8_char[0] = r;

    GHOST_EventKey *eventKey = new GHOST_EventKey(system->getMilliSeconds(),
                                                  key_down ? GHOST_kEventKeyDown
                                                           : GHOST_kEventKeyUp, window, key,
                                                  is_repeat, utf8_char);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 44 %d %d %d %d %d %d %d %d", key_down, key, is_repeat,is_repeated_modifier, keyaction, keyflags, keycode, keyMetaState);

    system->pushEvent(eventKey);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 45");
    return eventKey;
}

static bool checkClickPos(uint32_t width, uint32_t height, float posX, float posY) {
    int topLeftBtnPos = width / 80;
    if (posX < topLeftBtnPos) {
        if (posY > (height - topLeftBtnPos)) {
            return true;
        }
    }
    return false;
}

//  触摸屏事件
bool processButtonEvent(struct android_app *app, AInputEvent *event) {
    GHOST_SystemAndroid *system = (GHOST_SystemAndroid *) GHOST_ISystem::getSystem();
    if (system == nullptr) {
        return 0;
    }
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) system->getWindowManager()->getActiveWindow();
    if (window == nullptr) {
        return 0;
    }

    int32_t motionaction = AMotionEvent_getAction(event);
    uint32_t width, height;
    system->getMainDisplayDimensions(width, height);

    float msgPosX = AMotionEvent_getX(event, 0);    //  x坐标
    float msgPosY = AMotionEvent_getY(event, 0);    //  y坐标
    int64_t eventTime = AMotionEvent_getEventTime(event);         //  移动事件时间 {
    //  消息来源于触摸笔（蓝牙触摸笔）
    float pressure=AMotionEvent_getPressure(event,0);
    float size=AMotionEvent_getSize(event,0);

    system->m_x = -1;
    system->m_y = -1;
    //
    //  修改 打印日志
    std::string strInfo =
            "move " + std::to_string(motionaction) + " " + std::to_string(msgPosX) + " " +
            std::to_string(msgPosY)+"压感压力大小"+" "+std::to_string(pressure)+" "+"压感尺寸"+" "+std::to_string(size);
//    // CLOG_ERROR(&LOG, "交互processButtonEvent 5 %s", strInfo.c_str());
    bool checkMove = app->GetAsyncKeyState(101);
    GHOST_TabletData td;
    td.Xtilt=msgPosX;
    td.Ytilt=msgPosY;
    td.Pressure=pressure;
    td.Active=GHOST_TTabletMode::GHOST_kTabletModeStylus;
    uint64_t currentTime = system->getMilliSeconds();
    if (motionaction == AMOTION_EVENT_ACTION_DOWN) {
        system->m_lastDownx = msgPosX;
        system->m_lastDowny = msgPosY;
        //  当前窗口不是用户定义窗口
        // CLOG_ERROR(&LOG, "交互processButtonEvent 6  %s %d", strInfo.c_str(), window->m_shpeType);
        if (checkClickPos(width, height, msgPosX, msgPosY) && (21 != window->m_shpeType)) {
            // CLOG_ERROR(&LOG, "交互processButtonEvent 7 %s", strInfo.c_str());
            if (app->GetAsyncKeyState(100) == 0) {
                // CLOG_ERROR(&LOG, "交互processButtonEvent 8 %s", strInfo.c_str());
                //  点击左上角 打开设置页面
                system->m_lastClickTopLeftBtn = true;
                app->showWindow(app, 0, 0, 0, 0, 1001, "");
                // CLOG_ERROR(&LOG, "交互processButtonEvent 9 %s", strInfo.c_str());
                return 0;
            }
            // CLOG_ERROR(&LOG, "交互processButtonEvent 10 %s", strInfo.c_str());
        }
        // CLOG_ERROR(&LOG, "交互processButtonEvent 11 %s", strInfo.c_str());
        system->m_x = msgPosX;
        system->m_y = msgPosY;
        //  按下
        system->pushEvent(
                new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                      msgPosY, td));
        if (!checkMove) {
            system->pushEvent(
                    new GHOST_EventButton(currentTime, GHOST_TEventType::GHOST_kEventButtonDown,
                                          window, GHOST_SystemAndroid::currentButton(app), td));
        }
        // CLOG_ERROR(&LOG, "交互processButtonEvent 12 %s", strInfo.c_str());
    } else if (motionaction == AMOTION_EVENT_ACTION_UP) {
        // CLOG_ERROR(&LOG, "交互processButtonEvent 13");
        if (checkClickPos(width, height, system->m_lastDownx, system->m_lastDowny) &&
            (system->m_lastClickTopLeftBtn)) {
            // CLOG_ERROR(&LOG, "交互processButtonEvent 14");
            return 0;
        }
        // CLOG_ERROR(&LOG, "交互processButtonEvent 15");
        system->m_x = msgPosX;
        system->m_y = msgPosY;
        system->pushEvent(
                new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                      msgPosY, td));
        if (!checkMove) {
            system->pushEvent(
                    new GHOST_EventButton(currentTime, GHOST_TEventType::GHOST_kEventButtonUp,
                                          window, GHOST_SystemAndroid::currentButton(app), td));
        }
        for (int i = 3; i < 5; i++) {
            bool check = app->GetAsyncKeyState(i) == 1;
            if (check) {
                app->setValue(i, 0);
            }
        }
        // CLOG_ERROR(&LOG, "交互processButtonEvent 16");
    } else if (motionaction == AMOTION_EVENT_ACTION_MOVE) {
        if (checkClickPos(width, height, system->m_lastDownx, system->m_lastDowny) &&
            (system->m_lastClickTopLeftBtn)) {
            // CLOG_ERROR(&LOG, "交互processButtonEvent 17");
            return 0;
        }
        system->m_x = msgPosX;
        system->m_y = msgPosY;
        //  移动
        system->pushEvent(
                new GHOST_EventCursor(currentTime, GHOST_kEventCursorMove, window, msgPosX,
                                      msgPosY, td));
    }

    // CLOG_ERROR(&LOG, "交互processButtonEvent 18");
    return 1;
}

//  https://blog.csdn.net/zhou191954/article/details/22935607
/**
 * Process the next input event.
 */
static int32_t engine_handle_input(struct android_app *app, AInputEvent *event) {
    //  两种消息类型，一种是Motion，一种是Key
    int inputEventType = AInputEvent_getType(event);
//    CLOG_ERROR(&LOG, "交互engine_handle_input 1 %d",inputEventType);
    switch (inputEventType) {
        case AINPUT_EVENT_TYPE_MOTION: {
            // CLOG_ERROR(&LOG, "交互engine_handle_input 2");
            int32_t source = AInputEvent_getSource(event);
//            CLOG_ERROR(&LOG, "交互engine_handle_input 2 %d",source);
            switch (source) {
                case AINPUT_SOURCE_STYLUS:{
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 3");
                    //  消息来源于触摸笔
                    bool ret = processButtonEvent(app, event);
                    if(ret){
                        return 1;
                    }
                }
                break;
                case AINPUT_SOURCE_BLUETOOTH_STYLUS:{
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 4");
                    //  消息来源于触摸笔
                    bool ret = processButtonEvent(app, event);
                    if(ret){
                        return 1;
                    }
                }
                    break;
                case AINPUT_SOURCE_TOUCHSCREEN: {
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 5");
                    //  消息来源于触摸屏
                    bool ret = processButtonEvent(app, event);
                    if (ret){
                        return 1;
                    }
                }
                    break;
                case AINPUT_SOURCE_MOUSE: {
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 6");
                    //  鼠标
                    GHOST_Event *eventButton = processMouseEvent(app, event);
                    if (eventButton != nullptr) {
                        return 1;
                    }
                }
                    break;
                case AINPUT_SOURCE_TRACKBALL: {
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 7");
                    //  消息来源于TRACKBALL
                }
                    break;
                case AINPUT_SOURCE_TOUCHPAD: {
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 8");
                    //  消息来源于TOUCHPAD
                }
                    break;
                case AINPUT_SOURCE_JOYSTICK: {
                    // CLOG_ERROR(&LOG, "交互engine_handle_input 9");
                    //  消息来源于JOYSTICK
                }
                    break;
                default:
                    break;
            }
        }
            break;
        case AINPUT_EVENT_TYPE_KEY: {
            //  物理键盘或者虚拟键盘
            GHOST_EventKey *eventKey = processKeyEvent(app, event);
            if (eventKey!= nullptr){
                return 1;
            }
        }
            break;
        default:
            break;
    }
    return 0;
}

GHOST_SystemAndroid::GHOST_SystemAndroid(void *nativeWindow) : GHOST_System() { /* nop */
    timeval tv;
    if (gettimeofday(&tv, nullptr) == -1) {
        GHOST_ASSERT(false, "Could not instantiate timer!");
    }
    m_start_time = uint64_t(tv.tv_sec) * 1000 + tv.tv_usec / 1000;
    m_nativeWindow = nativeWindow;
    /* Background mode creates this system with no android_app (no Activity, no
     * Surface), so there is no input queue to attach to.  Dereferencing the null
     * here is what killed the process: it is the first thing that happens when
     * anything asks for a GL context, and it dereferences NULL at offset 0x10
     * (onInputEvent), long before EGL is reached.  Guarding it lets the offscreen
     * context path below actually run. */
    if (m_nativeWindow != nullptr) {
        struct android_app *app = (struct android_app *) m_nativeWindow;
        app->onInputEvent = engine_handle_input;
    }
}

#include <codecvt>

inline
std::string cpp11_codepoint_to_utf8(char32_t cp) // C++11 Sandard
{
    char utf8[4];
    char *end_of_utf8;

    char32_t const *from = &cp;

    std::mbstate_t mbs;
    std::codecvt_utf8<char32_t> ccv;

    if (ccv.out(mbs, from, from + 1, from, utf8, utf8 + 4, end_of_utf8))
        throw std::runtime_error("bad conversion");

    return {utf8, end_of_utf8};
}

bool GHOST_SystemAndroid::processEvents(bool waitForEvent) {

//    CLOG_ERROR(&LOG,"GHOST_SystemAndroid::processEvents 1");
    bool hasEventHandled = false;
    /* Process all the events waiting for us. */
    struct android_app *app = (struct android_app *) m_nativeWindow;
    int ident;
    int events;
    struct android_poll_source *source;
    // we loop until all events are read, then continue
    // to draw the next frame of animation.
    while (true) {
        bool checkRet = (ident = ALooper_pollOnce(0, nullptr, &events,
                                                  (void **) &source)) >= 0;
//        CLOG_ERROR(&LOG,"GHOST_SystemAndroid::processEvents 1 1 %d %d",checkRet,ident);
        if (!checkRet) {
            break;
        }
//        CLOG_ERROR(&LOG,"GHOST_SystemAndroid::processEvents 2");

        // Process this event.
        if (source != nullptr) {
            source->process(app, source);
            hasEventHandled = true;
        }
        // If a sensor has data, process it now.
        if (ident == LOOPER_ID_USER) {

        }
        // Check if we are exiting.
        if (app->destroyRequested != 0) {
            return hasEventHandled;
        }
    }
//    CLOG_ERROR(&LOG,"GHOST_SystemAndroid::processEvents keyboard 1 %d",m_keyEventStatus.size());
    while (!m_keyEventStatus.empty()) {
        KeyEventStatus keyEventStatus = m_keyEventStatus.front();
        char32_t unicode = keyEventStatus.m_p_unicode;
        int pressed = keyEventStatus.m_p_pressed;
        int physicalkeycode = keyEventStatus.m_p_physical_keycode;

        m_keyEventStatus.pop();

        char32_t prev_wc = 0;
        if ((unicode & 0xfffffc00) == 0xd800) {
            if (prev_wc != 0) {
            }
            prev_wc = unicode;
            continue; // Skip surrogate.
        }
        if ((unicode & 0xfffffc00) == 0xdc00) {
            if (prev_wc == 0) {
                continue; // Skip invalid surrogate.
            }
            unicode = (prev_wc << 10UL) + unicode - ((0xd800 << 10UL) + 0xdc00 - 0x10000);
            prev_wc = 0;
        } else {
            prev_wc = 0;
        }

        if (physicalkeycode > 0) {
            unicode = physicalkeycode;
        }
        int key = unicode;
        GHOST_TKey tKey = convertKey(key, 0, 0);
        char utf8_char[6] = {0};
        std::string stringUnicode = cpp11_codepoint_to_utf8(unicode);
        ::strcpy(utf8_char, stringUnicode.c_str());
        GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid::processEvents keyboard 2 %s %s %d %d %d", utf8_char,stringUnicode.c_str(), unicode, tKey, pressed);
        GHOST_EventKey *eventKey = new GHOST_EventKey(getMilliSeconds(),
                                                      pressed ? GHOST_kEventKeyDown
                                                              : GHOST_kEventKeyUp,
                                                      window,
                                                      tKey,
                                                      false,
                                                      utf8_char);

        pushEvent(eventKey);
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid::processEvents keyboard 3");
        hasEventHandled = true;
    }

    if (generateWindowExposeEvents()) {
        hasEventHandled = true;
    }

    processTrackpad();

    if (m_hasOtherEvents && m_tobeClosedWindow) {
        GHOST_IWindow *ghostIWindow = getWindowManager()->getWindowAssociatedWithEglWindow(
                app->window);
        if (getWindowManager()->getWindows().size() > 2) {
            ghostIWindow = getWindowManager()->getWindows()[
                    getWindowManager()->getWindows().size() - 2];
        }
//        GHOST_Event*event2 = processWindowEvent(GHOST_kEventWindowActivate, ghostIWindow);
//        pushEvent(event2);
        getWindowManager()->setActiveWindow(ghostIWindow);
        GHOST_Event *event1 = processWindowEvent(GHOST_kEventWindowClose, m_tobeClosedWindow);
        pushEvent(event1);
        hasEventHandled = true;

        m_hasOtherEvents = false;
        m_tobeClosedWindow = nullptr;
    }
    /* `PeekMessage` above is allowed to dispatch messages to the `wndproc` without us
     * noticing, so we need to check the event manager here to see if there are
     * events waiting in the queue. */
//    CLOG_ERROR(&LOG,"GHOST_SystemAndroid::processEvents 3 %d",hasEventHandled);
    hasEventHandled |= this->m_eventManager->getNumEvents() > 0;
    return hasEventHandled;
}

void GHOST_SystemAndroid::driveTrackpad() {
//    GHOST_WindowAndroid *active_window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
//    if (active_window) {
//        active_window->updateDirectManipulation();
//    }
}

void GHOST_SystemAndroid::processTrackpad() {
//    GHOST_WindowAndroid *active_window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
//    if (!active_window) {
//        return;
//    }
//    GHOST_TTrackpadInfo trackpad_info = active_window->getTrackpadInfo();
//    GHOST_WindowAndroid *system = (GHOST_WindowAndroid *) getSystem();
//
//    int32_t cursor_x, cursor_y;
//    system->getCursorPosition(cursor_x, cursor_y);
//
//    if (trackpad_info.x != 0 || trackpad_info.y != 0) {
//        system->pushEvent(new GHOST_EventTrackpad(system->getMilliSeconds(),
//                                                  active_window,
//                                                  GHOST_kTrackpadEventScroll,
//                                                  cursor_x,
//                                                  cursor_y,
//                                                  trackpad_info.x,
//                                                  trackpad_info.y,
//                                                  trackpad_info.isScrollDirectionInverted));
//    }
//    if (trackpad_info.scale != 0) {
//        system->pushEvent(new GHOST_EventTrackpad(system->getMilliSeconds(),
//                                                  active_window,
//                                                  GHOST_kTrackpadEventMagnify,
//                                                  cursor_x,
//                                                  cursor_y,
//                                                  trackpad_info.scale,
//                                                  0,
//                                                  false));
//    }
}


GHOST_IWindow *GHOST_SystemAndroid::createWindow(const char *title,
                                                 int32_t left,
                                                 int32_t top,
                                                 uint32_t width,
                                                 uint32_t height,
                                                 GHOST_TWindowState state,
                                                 GHOST_GLSettings gpuSettings,
                                                 int shape_type,
                                                 const bool /*exclusive*/,
                                                 const bool /*is_dialog*/,
                                                 const GHOST_IWindow *parentWindow) {
    struct android_app *app = (struct android_app *) m_nativeWindow;
    //  修改 如果已经创建了一个windows，则表示新创建的是一个对话框窗体 重要修改
    ANativeWindow *aNativeWindow = app->window;
    if (m_windowManager->getWindows().size() >= 1) {
        app->showWindow(app, left, top, width, height, shape_type, "");
        for (int i = 0; i < 10000; i++) {
            if (app->windowWindow) {
                break;
            }
            usleep(1000);
        }
        aNativeWindow = app->windowWindow;
    }
    GHOST_WindowAndroid *window = new GHOST_WindowAndroid(shape_type, this, title,
                                                          left,
                                                          top,
                                                          width,
                                                          height,
                                                          state,
                                                          parentWindow,
                                                          gpuSettings.context_type,
                                                          ((gpuSettings.flags &
                                                                  GHOST_glStereoVisual) !=
                                                           0), aNativeWindow);
    app->windowWindow = nullptr;
    if (window->getValid()) {
        /* Store the pointer to the window */
        m_windowManager->addWindow(window);
        m_windowManager->setActiveWindow(window);
    } else {
        delete window;
        window = nullptr;
    }
    return window;
}

void GHOST_SystemAndroid::getAllDisplayDimensions(uint32_t &width, uint32_t &height) const {
    getMainDisplayDimensions(width, height);
}

void
GHOST_SystemAndroid::getMainDisplayDimensions(uint32_t &width, uint32_t &height) const { /* nop */
    //  修改 平板尺寸
//    width=2000;
//    height=1200;
    //        width=2608;
    //        height=1220;
//    width=3200;
//    height=2136;

    struct android_app *app = (struct android_app *) m_nativeWindow;
    width = app->contentRect.right;
    height = app->contentRect.bottom;
}

GHOST_TSuccess GHOST_SystemAndroid::setCursorPosition(int32_t x, int32_t y) {
    std::string strInfo ="move setCursorPosition" + std::to_string(x) + " " + std::to_string(y);
//     CLOG_ERROR(&LOG, "交互 1 %s", strInfo.c_str());
    GHOST_WindowAndroid *window = window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
    struct android_app *app = (struct android_app *) m_nativeWindow;
    if ((app!= nullptr)&&(window!= nullptr)){
//        CLOG_ERROR(&LOG, "交互 2 %s", strInfo.c_str());
        app->setCursorPosition(x,y);
    }
//    GHOST_TabletData td;
//    pushEvent(new GHOST_EventCursor(getMilliSeconds(), GHOST_kEventCursorMove, window, x, y,td));
    m_x = x;
    m_y = y;
    return GHOST_kSuccess;
}

GHOST_TSuccess GHOST_SystemAndroid::getCursorPosition(int32_t &x, int32_t &y) const {
    std::string strInfo =
            "move getCursorPosition " + std::to_string(m_x) + " " + std::to_string(m_y);
    // CLOG_ERROR(&LOG, "交互%s", strInfo.c_str());
    if (m_x < 0) {
        return GHOST_kFailure;
    }
    if (m_y < 0) {
        return GHOST_kFailure;
    }
    x = m_x;
    y = m_y;
    return GHOST_kSuccess;
}

bool
GHOST_SystemAndroid::generateWindowExposeEvents() {
    std::vector<GHOST_WindowAndroid *>::iterator w_start = m_dirty_windows.begin();
    std::vector<GHOST_WindowAndroid *>::const_iterator w_end = m_dirty_windows.end();
    bool anyProcessed = false;

    for (; w_start != w_end; ++w_start) {
        GHOST_Event *g_event = new
                GHOST_Event(
                getMilliSeconds(),
                GHOST_kEventWindowUpdate,
                *w_start
        );

        (*w_start)->validate();

        if (g_event) {
            pushEvent(g_event);
            anyProcessed = true;
        }
    }

    m_dirty_windows.clear();
    return anyProcessed;
}

void
GHOST_SystemAndroid::addDirtyWindow(void *bad_wind) {
//    GHOST_ASSERT((bad_wind != NULL), "addDirtyWindow() NULL ptr trapped (window)");

    m_dirty_windows.push_back((GHOST_WindowAndroid *) bad_wind);
}

struct OBLButtonIDGhostKey {
    OBLButtonID oblButtonId;
    GHOST_TKey ghostTKey;
};

static OBLButtonIDGhostKey oBLButtonIDGhostKeys[] = {
        {OBLButtonID_Shift,        GHOST_kKeyLeftShift},
        {OBLButtonID_Ctrl,         GHOST_kKeyLeftControl},
        {OBLButtonID_Alt,          GHOST_kKeyLeftAlt},
        {OBLButtonID_Esc,          GHOST_kKeyEsc},
        {OBLButtonID_F2,           GHOST_kKeyF2},
        {OBLButtonID_F3,           GHOST_kKeyF3},
        {OBLButtonID_F4,           GHOST_kKeyF4},
        {OBLButtonID_F12,          GHOST_kKeyF12},
        {OBLButtonID_Home,         GHOST_kKeyHome},
        {OBLButtonID_Enter,        GHOST_kKeyEnter},
        {OBLButtonID_Tilde,        GHOST_kKeyAccentGrave},
        {OBLButtonID_1,            GHOST_kKey1},
        {OBLButtonID_2,            GHOST_kKey2},
        {OBLButtonID_3,            GHOST_kKey3},
        {OBLButtonID_4,            GHOST_kKey4},
        {OBLButtonID_5,            GHOST_kKey5},
        {OBLButtonID_Q,            GHOST_kKeyQ},
        {OBLButtonID_W,            GHOST_kKeyW},
        {OBLButtonID_T,            GHOST_kKeyT},
        {OBLButtonID_I,            GHOST_kKeyI},
        {OBLButtonID_O,            GHOST_kKeyO},
        {OBLButtonID_A,            GHOST_kKeyA},
        {OBLButtonID_X,            GHOST_kKeyX},
        {OBLButtonID_Y,            GHOST_kKeyY},
        {OBLButtonID_Z,            GHOST_kKeyZ},
        {OBLButtonID_C,            GHOST_kKeyC},
        {OBLButtonID_N,            GHOST_kKeyN},
        {OBLButtonID_M,            GHOST_kKeyM},
        {OBLButtonID_COMMA,        GHOST_kKeyComma},
        {OBLButtonID_PEROID,       GHOST_kKeyPeriod},
        {OBLButtonID_Space,        GHOST_kKeySpace},
        {OBLButtonID_PgUp,         GHOST_kKeyUpPage},
        {OBLButtonID_PgDn,         GHOST_kKeyDownPage},
        {OBLButtonID_UpArrow,      GHOST_kKeyUpArrow},
        {OBLButtonID_DownArrow,    GHOST_kKeyDownArrow},
        {OBLButtonID_LeftArrow,    GHOST_kKeyLeftArrow},
        {OBLButtonID_RightArrow,   GHOST_kKeyRightArrow},
        {OBLButtonID_Tab,          GHOST_kKeyTab},
        {OBLButtonID_Delete,       GHOST_kKeyDelete},
        {OBLButtonID_R,            GHOST_kKeyR},
        {OBLButtonID_S,            GHOST_kKeyS},
        {OBLButtonID_G,            GHOST_kKeyG},
        {OBLButtonID_H,            GHOST_kKeyH},
        {OBLButtonID_LeftSlash,    GHOST_kKeySlash},
        {OBLButtonID_D,            GHOST_kKeyD},
        {OBLButtonID_J,            GHOST_kKeyJ},
        {OBLButtonID_V,            GHOST_kKeyV},
        {OBLButtonID_E,            GHOST_kKeyE},
        {OBLButtonID_B,            GHOST_kKeyB},
        {OBLButtonID_F,            GHOST_kKeyF},
        {OBLButtonID_Num_0,        GHOST_kKeyNumpad0},
        {OBLButtonID_Num_1,        GHOST_kKeyNumpad1},
        {OBLButtonID_Num_2,        GHOST_kKeyNumpad2},
        {OBLButtonID_Num_3,        GHOST_kKeyNumpad3},
        {OBLButtonID_Num_4,        GHOST_kKeyNumpad4},
        {OBLButtonID_Num_5,        GHOST_kKeyNumpad5},
        {OBLButtonID_Num_Plus,     GHOST_kKeyNumpadPlus},
        {OBLButtonID_Num_Minus,    GHOST_kKeyNumpadMinus},
        {OBLButtonID_Num_Asterisk, GHOST_kKeyNumpadAsterisk},
        {OBLButtonID_Num_Slash,    GHOST_kKeyNumpadSlash},
        {OBLButtonID_Num_Period,   GHOST_kKeyNumpadPeriod},
        {OBLButtonID_Num_Enter,    GHOST_kKeyNumpadEnter},
};

//  快捷键键盘输入
void GHOST_SystemAndroid::setValueOn(int values[], int num) {
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
    char utf8_char[6] = {0};
    if (num == 1) {
        OBLButtonID oblButtonId = (OBLButtonID) values[0];
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValueOff 3 %d %d", num, values[0]);
        int oblButtonNum = sizeof(oBLButtonIDGhostKeys) / sizeof(oBLButtonIDGhostKeys[0]);
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValueOff 4 %d %d", num, values[0]);
        for (int i = 0; i < oblButtonNum; i++) {
            if (oBLButtonIDGhostKeys[i].oblButtonId == oblButtonId) {
                utf8_char[0] = oBLButtonIDGhostKeys[i].ghostTKey;
                GHOST_EventKey *eventKeyUp = new GHOST_EventKey(getMilliSeconds(),
                                                                GHOST_kEventKeyDown,
                                                                window,
                                                                oBLButtonIDGhostKeys[i].ghostTKey,
                                                                false,
                                                                utf8_char);
                pushEvent(eventKeyUp);
                // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValueOff 6 %d %d", num, values[0]);
                break;
            }
        }
    }
}

char32_t fix_unicode(char32_t p_char) {
    if (p_char >= 0x20 && p_char != 0x7F) {
        return p_char;
    }
    return 0;
}

void GHOST_SystemAndroid::inputKey(int p_physical_keycode,
                                   int p_unicode, int p_key_label, bool p_pressed,
                                   bool p_echo) {
    // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid inputKey 1 %d %d %d", p_physical_keycode, p_unicode,p_key_label);
    KeyEventStatus keyEventStatus{p_physical_keycode, p_unicode, p_key_label, p_pressed, p_echo};
    m_keyEventStatus.push(keyEventStatus);
    // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid inputKey 2");
}

void GHOST_SystemAndroid::wmInitReInit() {
    // CLOG_ERROR(&LOG, "交互wmInitReInit 1");
    uint32_t width, height;
    getMainDisplayDimensions(width, height);
    GHOST_WindowAndroid *windowNewActivate = nullptr;
    GHOST_WindowAndroid *windowNewActivateNew = nullptr;
    auto windows = getWindowManager()->getWindows();
    std::vector<GHOST_WindowAndroid *> windowsToRemove;
    std::vector<GHOST_WindowAndroid *> windowsToAdd;
    // CLOG_ERROR(&LOG, "交互wmInitReInit 2");
    for (auto window:windows) {
        // CLOG_ERROR(&LOG, "交互wmInitReInit 3");
        GHOST_WindowAndroid *nullWindow = (GHOST_WindowAndroid *) window;
        // CLOG_ERROR(&LOG, "交互wmInitReInit 4 %d %d %d", nullWindow->m_shpeType, width, height);
        if (getWindowManager()->getActiveWindow() == window) {
            // CLOG_ERROR(&LOG, "交互wmInitReInit 5");
            windowNewActivate = nullWindow;
        }
        // CLOG_ERROR(&LOG, "交互wmInitReInit 6");
        windowsToAdd.push_back(nullWindow);
        windowsToRemove.push_back(nullWindow);
        // CLOG_ERROR(&LOG, "交互wmInitReInit 7");
    }
    // CLOG_ERROR(&LOG, "交互wmInitReInit 8");
    for (auto window:windowsToRemove) {
        // CLOG_ERROR(&LOG, "交互wmInitReInit 9");
        getWindowManager()->removeWindow(window);
    }
    // CLOG_ERROR(&LOG, "交互wmInitReInit 10");
    for (auto *window:windowsToAdd) {
        struct android_app *app = (struct android_app *) m_nativeWindow;
        GHOST_WindowAndroid *windowNew = new GHOST_WindowAndroid(window->m_shpeType,
                                                                 this,
                                                                 "",
                                                                 0,
                                                                 0,
                                                                 width,
                                                                 height,
                                                                 GHOST_TWindowState::GHOST_kWindowStateFullScreen,
                                                                 nullptr,
                                                                 GHOST_TDrawingContextType::GHOST_kDrawingContextTypeOpenGL,
                                                                 false,
                                                                 app->window);
        // CLOG_ERROR(&LOG, "交互wmInitReInit 11 %d", windowNew->getValid());
        getWindowManager()->addWindow(windowNew);
        if (window == windowNewActivate) {
            windowNewActivateNew = windowNew;
        }
    }
    // CLOG_ERROR(&LOG, "交互wmInitReInit 12");
    if (windowNewActivateNew != nullptr) {
        // CLOG_ERROR(&LOG, "交互wmInitReInit 13");
        getWindowManager()->setActiveWindow(windowNewActivateNew);
    }

    // CLOG_ERROR(&LOG, "交互wmInitReInit 14");
}

//  快捷键键盘输入
void GHOST_SystemAndroid::setValueOff(int values[], int num) {
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
    char utf8_char[6] = {0};
    if (num == 1) {
        OBLButtonID oblButtonId = (OBLButtonID) values[0];
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValueOff 3 %d %d", num, values[0]);
        int oblButtonNum = sizeof(oBLButtonIDGhostKeys) / sizeof(oBLButtonIDGhostKeys[0]);
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValueOff 4 %d %d", num, values[0]);
        for (int i = 0; i < oblButtonNum; i++) {
            if (oBLButtonIDGhostKeys[i].oblButtonId == oblButtonId) {
                utf8_char[0] = oBLButtonIDGhostKeys[i].ghostTKey;
                GHOST_EventKey *eventKeyUp = new GHOST_EventKey(getMilliSeconds(),
                                                                GHOST_kEventKeyUp,
                                                                window,
                                                                oBLButtonIDGhostKeys[i].ghostTKey,
                                                                false,
                                                                utf8_char);
                pushEvent(eventKeyUp);
                // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValueOff 6 %d %d", num, values[0]);
                break;
            }
        }
    }
}

//  快捷键键盘输入
void GHOST_SystemAndroid::setValue(int values[], int num) {
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
    char utf8_char[6] = {0};
    if (num == 1) {
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 1 %d %d", num, values[0]);
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 2 %d %d", num, values[0]);
        OBLButtonID oblButtonId = (OBLButtonID) values[0];
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 3 %d %d", num, values[0]);
        int oblButtonNum = sizeof(oBLButtonIDGhostKeys) / sizeof(oBLButtonIDGhostKeys[0]);
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 4 %d %d", num, values[0]);
        for (int i = 0; i < oblButtonNum; i++) {
            if (oBLButtonIDGhostKeys[i].oblButtonId == oblButtonId) {
                utf8_char[0] = oBLButtonIDGhostKeys[i].ghostTKey;
                // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 5 %d %d", num, values[0]);
                GHOST_EventKey *eventKeyDown = new GHOST_EventKey(getMilliSeconds(),
                                                                  GHOST_kEventKeyDown,
                                                                  window,
                                                                  oBLButtonIDGhostKeys[i].ghostTKey,
                                                                  false,
                                                                  utf8_char);
                pushEvent(eventKeyDown);
                GHOST_EventKey *eventKeyUp = new GHOST_EventKey(getMilliSeconds(),
                                                                GHOST_kEventKeyUp,
                                                                window,
                                                                oBLButtonIDGhostKeys[i].ghostTKey,
                                                                false,
                                                                utf8_char);
                pushEvent(eventKeyUp);
                // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 6 %d %d", num, values[0]);
                break;
            }
        }
        // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid setValue 7 %d %d", num, values[0]);
        if (oblButtonId == OBLButtonID_ScrollUp) {
            GHOST_EventWheel *eventKey = new GHOST_EventWheel(getMilliSeconds(), window, 5);
            pushEvent(eventKey);
        } else if (oblButtonId == OBLButtonID_ScrollDown) {
            //  Scroll down
            GHOST_EventWheel *eventKey = new GHOST_EventWheel(getMilliSeconds(), window, -5);
            pushEvent(eventKey);
        } else if (oblButtonId == 10000) {
            GHOST_SystemAndroid *system = (GHOST_SystemAndroid *) GHOST_ISystem::getSystem();
            GHOST_TabletData td;
            uint64_t currentTime = system->getMilliSeconds();
            system->pushEvent(
                    new GHOST_EventButton(currentTime, GHOST_TEventType::GHOST_kEventButtonDown,
                                          window, GHOST_kButtonMaskLeft, td));
            system->pushEvent(
                    new GHOST_EventButton(currentTime, GHOST_TEventType::GHOST_kEventButtonUp,
                                          window, GHOST_kButtonMaskLeft, td));
        }
    }
}

GHOST_TSuccess GHOST_SystemAndroid::getModifierKeys(GHOST_ModifierKeys &keys) const {
    /* `GetAsyncKeyState` returns the current interrupt-level state of the hardware, which is needed
  * when passing key states to a newly-activated window - #40059. Alternative `GetKeyState` only
  * returns the state as processed by the thread's message queue. */

    struct android_app *app = (struct android_app *) m_nativeWindow;
    bool down = app->GetAsyncKeyState(0);
    keys.set(GHOST_kModifierKeyLeftShift, down || isShiftPressed);
    down = false;//(::GetAsyncKeyState(VK_RSHIFT)) != 0;
    keys.set(GHOST_kModifierKeyRightShift, down);
    bool down1 = down;

    down = app->GetAsyncKeyState(1);
    keys.set(GHOST_kModifierKeyLeftAlt, down || isAltPressed);
    down = false;//(::GetAsyncKeyState(VK_RMENU)) != 0;
    keys.set(GHOST_kModifierKeyRightAlt, down);
    bool down2 = down;

    down = app->GetAsyncKeyState(2);
    keys.set(GHOST_kModifierKeyLeftControl, down || isCtrlPressed);
    down = false;//(::GetAsyncKeyState(VK_RCONTROL)) != 0;
    keys.set(GHOST_kModifierKeyRightControl, down);
    bool down3 = down;

    down = false;//GetAsyncKeyState(VK_LWIN);
    keys.set(GHOST_kModifierKeyLeftOS, down);
    down = false;//(::GetAsyncKeyState(VK_RWIN)) != 0;
    keys.set(GHOST_kModifierKeyRightOS, down);

    // CLOG_ERROR(&LOG, "交互GHOST_SystemAndroid getModifierKeys %d %d %d", down1, down2, down3);

    return GHOST_kSuccess;
}

void GHOST_SystemAndroid::closeWindow() {
    struct android_app *app = (struct android_app *) m_nativeWindow;
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
    if (window->m_nativeWindow != app->window) {
        app->destroyedWindowWindow = false;
        app->showWindow(nullptr, -1, -1, -1, -1, window->m_shpeType, "");
        for (int i = 0; i < 10000; i++) {
            if (app->destroyedWindowWindow) {
                break;
            }
            usleep(1000);
        }
        app->destroyedWindowWindow = false;

        m_tobeClosedWindow = window;
        m_hasOtherEvents = true;
    }
}

GHOST_Event *GHOST_SystemAndroid::processWindowEvent(GHOST_TEventType type,
                                                     GHOST_IWindow *window) {
    if (type == GHOST_kEventWindowActivate) {
        getWindowManager()->setActiveWindow(window);
    }

    return new GHOST_Event(getMilliSeconds(), type, window);
}

void GHOST_SystemAndroid::showKeyboard(std::string p_existing_text,
                                       int p_type,
                                       int p_max_input_length,
                                       int p_cursor_start,
                                       int p_cursor_end) {
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 1");
    showHidenKeyboard(true, p_existing_text, p_type, p_max_input_length, p_cursor_start,
                      p_cursor_end);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 2");
}

void GHOST_SystemAndroid::hidenKeyboard() {
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 3");
    showHidenKeyboard(false, "", 0, 0, 0, 0);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 4");
}

void GHOST_SystemAndroid::showHidenKeyboard(bool show,
                                            std::string p_existing_text,
                                            int p_type,
                                            int p_max_input_length,
                                            int p_cursor_start,
                                            int p_cursor_end) {
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 4 1");
    struct android_app *app = (struct android_app *) m_nativeWindow;
    app->showWindow(app, p_type, p_max_input_length, p_cursor_start, p_cursor_end,
                    show ? 3000 : 4000, p_existing_text);
    return;

    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 5");
    GHOST_WindowAndroid *window = (GHOST_WindowAndroid *) getWindowManager()->getActiveWindow();
    ANativeWindow *nativeWindow = (ANativeWindow *) window->m_nativeWindow;
// Attaches the current thread to the JVM.
    jint lResult;
    jint lFlags = 0;
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 6");

    JavaVM *lJavaVM = app->activity->vm;
    JNIEnv *lJNIEnv = app->activity->env;

    JavaVMAttachArgs lJavaVMAttachArgs;
    lJavaVMAttachArgs.version = JNI_VERSION_1_6;
    lJavaVMAttachArgs.name = "NativeThread";
    lJavaVMAttachArgs.group = NULL;
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 7");

    lResult = lJavaVM->AttachCurrentThread(&lJNIEnv, &lJavaVMAttachArgs);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 8");
    if (lResult == JNI_ERR) {
        return;
    }

    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 9");
    // Retrieves NativeActivity.
    jobject lNativeActivity = app->activity->clazz;
    jclass ClassNativeActivity = lJNIEnv->GetObjectClass(lNativeActivity);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 10");

    // Retrieves Context.INPUT_METHOD_SERVICE.
    jclass ClassContext = lJNIEnv->FindClass("android/content/Context");
    jfieldID FieldINPUT_METHOD_SERVICE =
            lJNIEnv->GetStaticFieldID(ClassContext,
                                      "INPUT_METHOD_SERVICE", "Ljava/lang/String;");
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 11");
    jobject INPUT_METHOD_SERVICE =
            lJNIEnv->GetStaticObjectField(ClassContext,
                                          FieldINPUT_METHOD_SERVICE);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 12");
//    jniCheck(INPUT_METHOD_SERVICE);

    // Runs getSystemService(Context.INPUT_METHOD_SERVICE).
    jclass ClassInputMethodManager = lJNIEnv->FindClass(
            "android/view/inputmethod/InputMethodManager");
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 13");
    jmethodID MethodGetSystemService = lJNIEnv->GetMethodID(
            ClassNativeActivity, "getSystemService",
            "(Ljava/lang/String;)Ljava/lang/Object;");
    jobject lInputMethodManager = lJNIEnv->CallObjectMethod(
            lNativeActivity, MethodGetSystemService,
            INPUT_METHOD_SERVICE);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 14");

    // Runs getWindow().getDecorView().
    jmethodID MethodGetWindow = lJNIEnv->GetMethodID(
            ClassNativeActivity, "getWindow",
            "()Landroid/view/Window;");
    jobject lWindow = lJNIEnv->CallObjectMethod(lNativeActivity,
                                                MethodGetWindow);

    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 15");
    jclass ClassWindow = lJNIEnv->FindClass(
            "android/view/Window");
    jmethodID MethodGetDecorView = lJNIEnv->GetMethodID(
            ClassWindow, "getDecorView", "()Landroid/view/View;");
    jobject lDecorView = lJNIEnv->CallObjectMethod(lWindow,
                                                   MethodGetDecorView);
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 16");

    if (show) {
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 17");
        // Runs lInputMethodManager.showSoftInput(...).
        jmethodID MethodShowSoftInput = lJNIEnv->GetMethodID(
                ClassInputMethodManager, "showSoftInput",
                "(Landroid/view/View;I)Z");
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 18");
        jboolean lResult = lJNIEnv->CallBooleanMethod(
                lInputMethodManager, MethodShowSoftInput,
                lDecorView, lFlags);
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 19");
    } else {
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 20");
        // Runs lWindow.getViewToken()
        jclass ClassView = lJNIEnv->FindClass(
                "android/view/View");
        jmethodID MethodGetWindowToken = lJNIEnv->GetMethodID(
                ClassView, "getWindowToken", "()Landroid/os/IBinder;");
        jobject lBinder = lJNIEnv->CallObjectMethod(lDecorView,
                                                    MethodGetWindowToken);
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 21");

        // lInputMethodManager.hideSoftInput(...).
        jmethodID MethodHideSoftInput = lJNIEnv->GetMethodID(
                ClassInputMethodManager, "hideSoftInputFromWindow",
                "(Landroid/os/IBinder;I)Z");
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 22");
        jboolean lRes = lJNIEnv->CallBooleanMethod(
                lInputMethodManager, MethodHideSoftInput,
                lBinder, lFlags);
        // CLOG_ERROR(&LOG, "交互showHidenKeyboard 23");
    }

    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 24");
    // Finished with the JVM.
    lJavaVM->DetachCurrentThread();
    // CLOG_ERROR(&LOG, "交互showHidenKeyboard 25");
}

GHOST_TButton GHOST_SystemAndroid::currentButton(android_app *app) {
    int mmbRet = app->GetAsyncKeyState(3);
    if (mmbRet) {
        return GHOST_kButtonMaskMiddle;
    }
    int rmbRet = app->GetAsyncKeyState(4);
    if (rmbRet) {
        return GHOST_kButtonMaskRight;
    }
    return GHOST_kButtonMaskLeft;
}

GHOST_TCapabilityFlag GHOST_SystemAndroid::getCapabilities() const{
    return GHOST_TCapabilityFlag(GHOST_CAPABILITY_FLAG_ALL &
                                 /* No windowing functionality supported. */
                                 ~(GHOST_kCapabilityWindowPosition | GHOST_kCapabilityCursorWarp |
                                   GHOST_kCapabilityPrimaryClipboard |
                                   GHOST_kCapabilityClipboardImages));
}

inline size_t count_utf_8_from_16(const wchar_t *string16)
{
    int i;
    size_t count = 0;
    wchar_t u = 0;
    if (!string16) {
        return 0;
    }

    for (i = 0; (u = string16[i]); i++) {
        if (u < 0x0080) {
            count += 1;
        }
        else {
            if (u < 0x0800) {
                count += 2;
            }
            else {
                if (u < 0xD800) {
                    count += 3;
                }
                else {
                    if (u < 0xDC00) {
                        i++;
                        if ((u = string16[i]) == 0) {
                            break;
                        }
                        if (u >= 0xDC00 && u < 0xE000) {
                            count += 4;
                        }
                    }
                    else {
                        if (u < 0xE000) {
                            /* Illegal. */
                        }
                        else {
                            count += 3;
                        }
                    }
                }
            }
        }
    }

    return ++count;
}

inline char *alloc_utf_8_from_16(const wchar_t *in16, size_t add)
{
    size_t bsize = count_utf_8_from_16(in16);
    char *out8 = NULL;
    if (!bsize) {
        return NULL;
    }
    out8 = (char *)malloc(sizeof(char) * (bsize + add));
    conv_utf_16_to_8(in16, out8, bsize);
    return out8;
}

inline size_t count_utf_16_from_8(const char *string8)
{
    size_t count = 0;
    char u;
    char type = 0;
    unsigned int u32 = 0;

    if (!string8) {
        return 0;
    }

    for (; (u = *string8); string8++) {
        if (type == 0) {
            if ((u & 0x01 << 7) == 0) {
                count++;
                u32 = 0;
                continue;
            }  // 1 utf-8 char
            if ((u & 0x07 << 5) == 0xC0) {
                type = 1;
                u32 = u & 0x1F;
                continue;
            }  // 2 utf-8 char
            if ((u & 0x0F << 4) == 0xE0) {
                type = 2;
                u32 = u & 0x0F;
                continue;
            }  // 3 utf-8 char
            if ((u & 0x1F << 3) == 0xF0) {
                type = 3;
                u32 = u & 0x07;
                continue;
            }  // 4 utf-8 char
            continue;
        }
        if ((u & 0xC0) == 0x80) {
            u32 = (u32 << 6) | (u & 0x3F);
            type--;
        }
        else {
            u32 = 0;
            type = 0;
        }

        if (type == 0) {
            if ((0 < u32 && u32 < 0xD800) || (0xE000 <= u32 && u32 < 0x10000)) {
                count++;
            }
            else if (0x10000 <= u32 && u32 < 0x110000) {
                count += 2;
            }
            u32 = 0;
        }
    }

    return ++count;
}

inline int conv_utf_8_to_16(const char *in8, wchar_t *out16, size_t size16)
{
    char u;
    char type = 0;
    unsigned int u32 = 0;
    wchar_t *out16end = out16 + size16;
    int err = 0;
    if (!size16 || !in8 || !out16) {
        return UTF_ERROR_NULL_IN;
    }
    out16end--;

    for (; out16 < out16end && (u = *in8); in8++) {
        if (type == 0) {
            if ((u & 0x01 << 7) == 0) {
                *out16 = u;
                out16++;
                u32 = 0;
                continue;
            }  // 1 utf-8 char
            if ((u & 0x07 << 5) == 0xC0) {
                type = 1;
                u32 = u & 0x1F;
                continue;
            }  // 2 utf-8 char
            if ((u & 0x0F << 4) == 0xE0) {
                type = 2;
                u32 = u & 0x0F;
                continue;
            }  // 3 utf-8 char
            if ((u & 0x1F << 3) == 0xF0) {
                type = 3;
                u32 = u & 0x07;
                continue;
            }  // 4 utf-8 char
            err |= UTF_ERROR_ILLCHAR;
            continue;
        }
        if ((u & 0xC0) == 0x80) {
            u32 = (u32 << 6) | (u & 0x3F);
            type--;
        }
        else {
            u32 = 0;
            type = 0;
            err |= UTF_ERROR_ILLSEQ;
        }

        if (type == 0) {
            if ((0 < u32 && u32 < 0xD800) || (0xE000 <= u32 && u32 < 0x10000)) {
                *out16 = u32;
                out16++;
            }
            else if (0x10000 <= u32 && u32 < 0x110000) {
                if (out16 + 1 >= out16end) {
                    break;
                }
                u32 -= 0x10000;
                *out16 = 0xD800 + (u32 >> 10);
                out16++;
                *out16 = 0xDC00 + (u32 & 0x3FF);
                out16++;
            }
            u32 = 0;
        }
    }

    *out16 = *out16end = 0;

    if (*in8) {
        err |= UTF_ERROR_SMALL;
    }

    return err;
}


char *GHOST_SystemAndroid::getClipboard(bool selection) const{
    struct android_app *app = (struct android_app *) m_nativeWindow;
    std::wstring clipBoardData=app->getClipboard(selection);
    if (clipBoardData.empty()){
        return nullptr;
    }
    char *temp_buff = alloc_utf_8_from_16(clipBoardData.c_str(), 0);
    return temp_buff;
}

void GHOST_SystemAndroid::putClipboard(const char * buffer, bool selection) const{
    if (selection || !buffer) {
        return;
    } /* For copying the selection, used on X11. */
    size_t len = count_utf_16_from_8(buffer);
    wchar_t *data = new wchar_t[len];
    conv_utf_8_to_16(buffer, data, len);
    struct android_app *app = (struct android_app *) m_nativeWindow;
    app->putClipboard(data,selection);
}
