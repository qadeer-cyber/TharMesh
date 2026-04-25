// SPDX-License-Identifier: LicenseRef-TharMesh-Proprietary
// Copyright (c) 2026 Abdul Qadeer / Qadeer Cyber. All rights reserved.
// Proprietary and confidential. Unauthorized copying, modification,
// distribution, or use is strictly prohibited. See LICENSE for details.

package com.tharmesh.dtn

enum class ProtocolType {
    HELLO,
    INV,
    GET,
    BUNDLE,
    ACK,
    READ
}

data class ProtocolFrame(
    val type: ProtocolType,
    val fromPeerId: String,
    val payload: String
)
