package com.mardillu.multiscanner.ui.camera

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuItemCompat
import com.mardillu.multiscanner.R
import me.dm7.barcodescanner.zbar.Result
import me.dm7.barcodescanner.zbar.ZBarScannerView

class CodeScannerActivity : AppCompatActivity(), ZBarScannerView.ResultHandler {
    private var mScannerView: ZBarScannerView? = null
    private var mFlash = false
    private var mAutoFocus = false
    private var mCameraId = -1
    var type: String? = null
    @SuppressLint("SourceLockedOrientationActivity")
    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val intent = intent
        type = intent.getStringExtra("type")
        Log.d("Type from TabScanner", type!!)
        mScannerView = ZBarScannerView(this)
        setContentView(mScannerView)
    }

    private fun continueSetup(state: Bundle?) {
        val intent = intent
        type = intent.getStringExtra("type")
        Log.d("Type from TabScanner", type!!)
        if (state != null) {
            mFlash = state.getBoolean(FLASH_STATE, false)
            mAutoFocus = state.getBoolean(AUTO_FOCUS_STATE, true)
            mCameraId = state.getInt(CAMERA_ID, -1)
        } else {
            mFlash = false
            mAutoFocus = true
            mCameraId = -1
        }
        mScannerView = ZBarScannerView(this)
        setContentView(mScannerView)
        onResume()
    }

    public override fun onResume() {
        super.onResume()
        if (mScannerView != null) {
            mScannerView!!.setResultHandler(this)
            mScannerView!!.startCamera()
        }
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(FLASH_STATE, mFlash)
        outState.putBoolean(AUTO_FOCUS_STATE, mAutoFocus)
        outState.putInt(CAMERA_ID, mCameraId)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        var menuItem: MenuItem? = if (mFlash) {
            menu.add(Menu.NONE, R.id.menu_flash, 0, R.string.flash_on)
        } else {
            menu.add(Menu.NONE, R.id.menu_flash, 0, R.string.flash_off)
        }
        menuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menuItem = if (mAutoFocus) {
            menu.add(Menu.NONE, R.id.menu_auto_focus, 0, R.string.auto_focus_on)
        } else {
            menu.add(Menu.NONE, R.id.menu_auto_focus, 0, R.string.auto_focus_off)
        }
        menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle presses on the action bar items
        if (mScannerView == null) {
            return super.onOptionsItemSelected(item)
        }
        val i = item.itemId
        if (i == R.id.menu_flash) {
            mFlash = !mFlash
            if (mFlash) {
                item.setTitle(R.string.flash_on)
            } else {
                item.setTitle(R.string.flash_off)
            }
            mScannerView!!.flash = mFlash
            return true
        } else if (i == R.id.menu_auto_focus) {
            mAutoFocus = !mAutoFocus
            if (mAutoFocus) {
                item.setTitle(R.string.auto_focus_on)
            } else {
                item.setTitle(R.string.auto_focus_off)
            }
            mScannerView!!.setAutoFocus(mAutoFocus)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun handleResult(rawResult: Result) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tg.startTone(ToneGenerator.TONE_PROP_PROMPT)
        } catch (ignored: Exception) {
        }
        Log.d("scanner_result", rawResult.contents)
        val intent = Intent()
        intent.putExtra("scanner_result", rawResult.contents)
        setResult(RESULT_OK, intent)
        finish()
    }

    public override fun onPause() {
        if (mScannerView != null) {
            mScannerView!!.stopCamera()
        }
        super.onPause()
    }

    companion object {
        private const val FLASH_STATE = "FLASH_STATE"
        private const val AUTO_FOCUS_STATE = "AUTO_FOCUS_STATE"
        private const val CAMERA_ID = "CAMERA_ID"
    }
}