/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.R;

public class EmergencyCarrierArea extends LinearLayout {

    private CarrierText mCarrierText;
    private EmergencyButton mEmergencyButton;
    // Modify 20130912 Spreadst of Bug 215339 support support multi-card carrier info display
    private CarriersTextLayout mCarriersTextLayout;

    public EmergencyCarrierArea(Context context) {
        super(context);
    }

    public EmergencyCarrierArea(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCarrierText = (CarrierText) findViewById(R.id.carrier_text);
        /* Modify 20130912 Spreadst of Bug 215339 support support multi-card carrier info display @{ */
        mCarrierText.setVisibility(View.GONE);
        mEmergencyButton = (EmergencyButton) findViewById(R.id.emergency_call_button);
        mCarriersTextLayout = (CarriersTextLayout) findViewById(R.id.carriers_layout);

        // The emergency button overlaps the carrier text, only noticeable when highlighted.
        // So temporarily hide the carrier text while the emergency button is pressed.
        mEmergencyButton.setOnTouchListener(new OnTouchListener(){
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        /* SPRD: Modify 20130912 Spreadst of Bug 215343 lockscreen show emergency call when no card no service @{ */
                        if (mEmergencyButton.canEmergencyCall()){
                            mCarrierText.animate().alpha(0);
                            mCarriersTextLayout.animate().alpha(0);
                        }
                        /* @} */
                        break;
                    case MotionEvent.ACTION_UP:
                        mCarrierText.animate().alpha(1);
                        mCarriersTextLayout.animate().alpha(1);
                        /* @} */
                        break;
                }
                return false;
            }});
    }
}
