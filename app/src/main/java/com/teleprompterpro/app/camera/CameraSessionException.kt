package com.teleprompterpro.app.camera

import com.teleprompterpro.app.util.AppError

/** Thrown by [SingleCameraSession.open] with a user-facing [AppError]. */
class CameraSessionException(val error: AppError, cause: Throwable? = null) :
    Exception(error.message, cause)
