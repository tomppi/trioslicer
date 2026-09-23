/* SPDX-FileCopyrightText: 2011-2023 Blender Foundation
 *
 * SPDX-License-Identifier: GPL-2.0-or-later */

/** \file
 * \ingroup GHOST
 * Declaration of GHOST_WindowAndroid class.
 */

#pragma once

#include "GHOST_Window.hh"
#include "GHOST_ContextEGL.hh"

#include <map>


class GHOST_SystemHeadless;
class GHOST_SystemAndroid;
class GHOST_WindowAndroid : public GHOST_Window {
public:
    void *m_nativeWindow = nullptr;
public:
    GHOST_WindowAndroid(int shapeType,void*pSystem,const char *title,
                     int32_t /*left*/,
                     int32_t /*top*/,
                     uint32_t width,
                     uint32_t height,
                     GHOST_TWindowState state,
                     const GHOST_IWindow * /*parentWindow*/,
                     GHOST_TDrawingContextType /*type*/,
                     const bool stereoVisual,
                     void *nativeWindow);

    ~GHOST_WindowAndroid() override = default;

protected:
    GHOST_TSuccess installDrawingContext(GHOST_TDrawingContextType /*type*/) {
        return GHOST_kSuccess;
    }

    GHOST_TSuccess removeDrawingContext() {
        return GHOST_kSuccess;
    }

    void setTitle(const char * /*title*/) override { /* nothing */
    }

    std::string getTitle() const override {
        return "untitled";
    }

    void getWindowBounds(GHOST_Rect &bounds) const override {
        getClientBounds(bounds);
    }

    void getClientBounds(GHOST_Rect &bounds) const override;

    GHOST_TSuccess setClientWidth(uint32_t /*width*/) override {
        return GHOST_kSuccess;
    }

    GHOST_TSuccess setClientHeight(uint32_t /*height*/) override {
        return GHOST_kSuccess;
    }

    GHOST_TSuccess setClientSize(uint32_t /*width*/, uint32_t /*height*/) override {
        return GHOST_kSuccess;
    }

    void screenToClient(int32_t inX, int32_t inY, int32_t &outX, int32_t &outY) const override {
        outX = inX;
        outY = inY;
    }

    void clientToScreen(int32_t inX, int32_t inY, int32_t &outX, int32_t &outY) const override {
        outX = inX;
        outY = inY;
    }

    GHOST_TSuccess setState(GHOST_TWindowState /*state*/) override {
        return GHOST_kSuccess;
    }

    GHOST_TWindowState getState() const override {
        return GHOST_kWindowStateFullScreen;
    }

//    GHOST_TSuccess swapBuffers() override
//    {
////        aSwapBuffers();
//    this->getContext()->swapBuffers();
//        return GHOST_kSuccess;
//    }

    bool m_invalid_window= false;
    GHOST_SystemAndroid *m_system;
    GHOST_TSuccess invalidate() override;

    GHOST_TSuccess setOrder(GHOST_TWindowOrder /*order*/) override {
        return GHOST_kSuccess;
    }

    GHOST_TSuccess beginFullScreen() const override {
        return GHOST_kSuccess;
    }

    GHOST_TSuccess endFullScreen() const override {
        return GHOST_kSuccess;
    }
public:
    void validate(){
        m_invalid_window = false;
    }
private:
    /**
     * \param type: The type of rendering context create.
     * \return Indication of success.
     */
    GHOST_Context *newDrawingContext(GHOST_TDrawingContextType /*type*/) override {
        /* Same Android constraint as the offscreen path: only OpenGL ES can be
         * bound through EGL here, so request an ES 3.2 context rather than
         * desktop GL 4.x. */
        GHOST_Context *context = new GHOST_ContextEGL(
            (GHOST_System *) m_system,
            false,
            (EGLNativeWindowType) m_nativeWindow,
            EGLNativeDisplayType(EGL_DEFAULT_DISPLAY),
            0, /* no profile mask: not applicable to ES */
            3,
            2,
            GHOST_OPENGL_EGL_CONTEXT_FLAGS,
            GHOST_OPENGL_EGL_RESET_NOTIFICATION_STRATEGY,
            EGL_OPENGL_ES_API);

        if (context->initializeDrawingContext()) {
            return context;
        }
        delete context;
        return nullptr;
    }

    void *getEGLWindow() const override {
        return m_nativeWindow;
    }
    /**
      * Sets the cursor visibility on the window using
      * native window system calls.
      */
    GHOST_TSuccess setWindowCursorVisibility(bool visible) override ;

    /**
     * Sets the cursor grab on the window using native window system calls.
     * Using registerMouseClickEvent.
     * \param mode: GHOST_TGrabCursorMode.
     */
    GHOST_TSuccess setWindowCursorGrab(GHOST_TGrabCursorMode mode) override ;

    /**
     * Sets the cursor shape on the window using
     * native window system calls.
     */
    GHOST_TSuccess setWindowCursorShape(GHOST_TStandardCursor shape) override ;
    GHOST_TSuccess hasCursorShape(GHOST_TStandardCursor shape) override ;

    /**
     * Sets the cursor shape on the window using
     * native window system calls.
     */
    GHOST_TSuccess setWindowCustomCursorShape(uint8_t *bitmap,
                                              uint8_t *mask,
                                              int sizex,
                                              int sizey,
                                              int hotX,
                                              int hotY,
                                              bool canInvertColor) override ;

private:
    void loadCursor(bool visible, GHOST_TStandardCursor shape) const;
    bool IsCurrentWindow();
public:
    int m_shpeType=0;
private:
    long m_customCursor=0;
    long getStandardCursor(GHOST_TStandardCursor shape)const;
    void SetCursor(long cursor)const;
    bool ShowCursor(bool visible)const;
    long CreateCursor(int ,int hotX,int hotY,int ,int ,uint32_t andData[32], uint32_t xorData[32]);
    void DestroyCursor(long cursor);
};

