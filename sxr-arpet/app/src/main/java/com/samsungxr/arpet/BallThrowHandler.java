/* Copyright 2015 Samsung Electronics Co., LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samsungxr.arpet;

import android.view.GestureDetector;
import android.view.MotionEvent;

import com.samsungxr.IApplicationEvents;
import com.samsungxr.SXRCollider;
import com.samsungxr.SXRComponent;
import com.samsungxr.SXREventListeners;
import com.samsungxr.SXRMeshCollider;
import com.samsungxr.SXRNode;
import com.samsungxr.SXRRenderData;
import com.samsungxr.io.SXRTouchPadGestureListener;
import com.samsungxr.mixedreality.SXRPlane;
import com.samsungxr.physics.SXRRigidBody;

import com.samsungxr.arpet.constant.PetConstants;
import com.samsungxr.arpet.service.IMessageService;
import com.samsungxr.arpet.service.MessageService;
import com.samsungxr.arpet.service.data.BallCommand;
import com.samsungxr.arpet.service.event.BallCommandReceivedMessage;
import com.samsungxr.arpet.service.share.PlayerSceneObject;
import com.samsungxr.arpet.util.EventBusUtils;
import com.samsungxr.arpet.util.LoadModelHelper;
import org.greenrobot.eventbus.Subscribe;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class BallThrowHandler {

    private static final String TAG = BallThrowHandler.class.getSimpleName();

    private static final float defaultPositionX = 0f;
    private static final float defaultPositionY = 0f;
    private static final float defaultPositionZ = -40f;

    private static final float defaultScale = 0.2f;

    private static final float MIN_Y_OFFSET = 3 * 100;

    private PlayerSceneObject mPlayer;
    private final PetContext mPetContext;
    private SXRNode mBall;
    private SXRRigidBody mRigidBody;

    private IApplicationEvents mEventListener;
    private boolean thrown = false;

    private SXRPlane firstPlane = null; // Maybe this could be replaced by a boolean
    private boolean mResetOnTouchEnabled = true;

    private final float mDirTan;
    private float mForce;
    private final Vector3f mForceVector;

    private IMessageService mMessageService;

    BallThrowHandler(PetContext petContext) {
        mPetContext = petContext;
        mPlayer = petContext.getPlayer();

        createBall();
        initController();

        // Throw the ball at 45 degrees
        mDirTan = (float) Math.tan(Math.PI / 4.0);
        mForce = 1f;
        mForceVector = new Vector3f(mDirTan, mDirTan, -1.0f);

        EventBusUtils.register(this);

        mMessageService = MessageService.getInstance();
    }

    @Subscribe
    public void handleReceivedMessage(BallCommandReceivedMessage message) {
        BallCommand command = message.getBallCommand();
        if (BallCommand.THROW.equals(command.getType())) {
            throwLocalBall(command.getForceVector());
        }
    }

    public void enable() {

        final SXRNode parent = mBall.getParent();
        mBall.getTransform().setPosition(defaultPositionX, defaultPositionY, defaultPositionZ);

        if (parent != null) {
            parent.removeChildObject(mBall);
        }

        //mBall.getRenderData().setRenderingOrder(GVRRenderData.GVRRenderingOrder.OVERLAY);

        mPlayer.addChildObject(mBall);
        mPetContext.getSXRContext().getApplication().getEventReceiver().addListener(mEventListener);
    }

    public void disable() {
        final SXRNode parent = mBall.getParent();
        thrown = false;
        resetRigidBody();
        if (parent != null) {
            parent.removeChildObject(mBall);
        }
        mPetContext.getSXRContext().getApplication().getEventReceiver().removeListener(mEventListener);
    }

    private void createBall() {
        load3DModel();

        createBoneCollider();

        mBall.getTransform().setPosition(defaultPositionX, defaultPositionY, defaultPositionZ);
        mBall.getTransform().setScale(defaultScale, defaultScale, defaultScale);

        mRigidBody = new SXRRigidBody(mPetContext.getSXRContext(), 5.0f);
        mRigidBody.setRestitution(0.5f);
        mRigidBody.setFriction(0.5f);
        mRigidBody.setCcdMotionThreshold(0.001f);
        mRigidBody.setCcdSweptSphereRadius(2f);

        mBall.attachComponent(mRigidBody);
        mRigidBody.setEnable(false);
    }

    private void createBoneCollider() {
        mBall.forAllComponents(new SXRNode.ComponentVisitor() {
            @Override
            public boolean visit(SXRComponent sxrComponent) {
                if (mBall.getCollider() == null) {
                    SXRCollider collider = new SXRMeshCollider(mPetContext.getSXRContext(),
                            ((SXRRenderData) sxrComponent).getMesh().getBoundingBox());
                    mBall.attachCollider(collider);
                }
                return false;
            }
        }, SXRRenderData.getComponentType());
    }

    @Subscribe
    public void onSXRWorldReady(PlaneDetectedEvent event) {
        this.firstPlane = event.getPlane();
    }

    private void initController() {
        final SXRTouchPadGestureListener gestureListener = new SXRTouchPadGestureListener() {
            @Override
            public boolean onDown(MotionEvent arg0) {
                if (firstPlane != null && mResetOnTouchEnabled && thrown) {
                    reset();
                }
                return false;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) {
                    return false;
                }

                if (mPetContext.getMode() != PetConstants.SHARE_MODE_GUEST
                        && firstPlane != null) {
                    final float vlen = (float) Math.sqrt((vx * vx) + (vy * vy));
                    final float vz = vlen / mDirTan;

                    mForce = 50 * vlen / (float) (e2.getEventTime() - e1.getDownTime());
                    mForceVector.set(mForce * -vx, mForce * vy, mForce * -vz);

                    throwRemoteBall(mForceVector);

                    throwLocalBall(mForceVector);
                    return true;
                }

                return false;
            }
        };

        final GestureDetector gestureDetector = new GestureDetector(mPetContext.getActivity(), gestureListener);
        mEventListener = new SXREventListeners.ApplicationEvents() {
            @Override
            public void dispatchTouchEvent(MotionEvent event) {
                gestureDetector.onTouchEvent(event);
            }
        };
    }

    private void throwRemoteBall(Vector3f forceVector) {
        BallCommand throwCommand = new BallCommand(BallCommand.THROW);
        throwCommand.setForceVector(forceVector);
        mMessageService.sendBallCommand(throwCommand);
    }

    // FIXME: Why multiply by root matrix?
    private void throwLocalBall(Vector3f forceVector) {
        //Matrix4f rootMatrix = mPetContext.getMainScene().getRoot().getTransform().getModelMatrix4f();
        //rootMatrix.invert();

        // Calculating the new model matrix (T') for the ball: T' = iP x T
        Matrix4f ballMatrix = mBall.getTransform().getModelMatrix4f();
        //rootMatrix.mul(ballMatrix, ballMatrix);

        // Add the ball as physics root child...
        mBall.getParent().removeChildObject(mBall);
        // mBall.getRenderData().setRenderingOrder(GVRRenderData.GVRRenderingOrder.GEOMETRY);
        mPetContext.getMainScene().addNode(mBall);

        // ... And set its model matrix to keep the same world matrix
        mBall.getTransform().setModelMatrix(ballMatrix);

        // Force vector will be based on player rotation...
        Matrix4f playerMatrix = mPlayer.getTransform().getModelMatrix4f();

        // ... And same transformation is required
        //rootMatrix.mul(playerMatrix, playerMatrix);
        Quaternionf q = new Quaternionf();
        q.setFromNormalized(playerMatrix);
        forceVector.rotate(q);

        mRigidBody.setEnable(true);
        mRigidBody.applyCentralForce(forceVector.x(), forceVector.y(), forceVector.z());
        thrown = true;
        EventBusUtils.post(new BallThrowHandlerEvent(BallThrowHandlerEvent.THROWN));
    }

    private void resetRigidBody() {
        mRigidBody.setLinearVelocity(0f, 0f, 0f);
        mRigidBody.setAngularVelocity(0f, 0f, 0f);
        mRigidBody.setEnable(false);
    }

    public SXRNode getBall() {
        return mBall;
    }

    public void reset() {
        resetRigidBody();
        SXRNode parent = mBall.getParent();
        if (parent != null) {
            parent.removeChildObject(mBall);
        }
        mBall.getTransform().setPosition(defaultPositionX, defaultPositionY, defaultPositionZ);
        mBall.getTransform().setScale(defaultScale, defaultScale, defaultScale);
        mBall.getTransform().setRotation(1, 0, 0, 0);
        //mBall.getRenderData().setRenderingOrder(GVRRenderData.GVRRenderingOrder.OVERLAY);

        mPlayer.addChildObject(mBall);
        mResetOnTouchEnabled = true;
        thrown = false;
        EventBusUtils.post(new BallThrowHandlerEvent(BallThrowHandlerEvent.RESET));
    }

    public boolean canBeReseted() {
        return thrown && mPlayer.getTransform().getPositionY() - mBall.getTransform().getPositionY() > MIN_Y_OFFSET;
    }

    private void load3DModel() {
        mBall = LoadModelHelper.loadSceneObject(mPetContext.getSXRContext(),
                LoadModelHelper.BALL_MODEL_PATH);
    }
}
