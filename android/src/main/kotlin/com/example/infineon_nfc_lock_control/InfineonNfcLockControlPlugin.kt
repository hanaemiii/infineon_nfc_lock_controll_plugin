package com.example.infineon_nfc_lock_control

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.infineon.smack.sdk.SmackSdk
import com.infineon.smack.sdk.android.AndroidNfcAdapterWrapper
import com.infineon.smack.sdk.log.AndroidSmackLogger
import com.infineon.smack.sdk.nfc.NfcAdapterWrapper
import com.infineon.smack.sdk.smack.DefaultSmackClient
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

/** InfineonNfcLockControlPlugin */
class InfineonNfcLockControlPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private var applicationContext: Context? = null
    private var currentActivity: Activity? = null

    private var smackSdk: SmackSdk? = null
    private var nfcAdapterWrapper: NfcAdapterWrapper? = null
    private var registrationViewModel: RegistrationViewModel? = null

    // --- NEW: Flag to track if the plugin is initialized ---
    private var isPluginInitialized: Boolean = false

    companion object {
        private const val TAG = "InfineonNfcLockPlugin"
        private const val CHANNEL = "infineon_nfc_lock_control"
    }
    private var isLockPresent: Boolean = false

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        Log.d(TAG, "onAttachedToEngine: Plugin channel setup.")
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addOnNewIntentListener { intent ->
            handleNewIntent(intent)
            true
        }
        Log.d(TAG, "onAttachedToActivity: Activity attached.")
        // Initialize SmackSdk and ViewModel here, as Activity is available
        initializeSmackAndViewModel()
    }

    private fun initializeSmackAndViewModel() {
        applicationContext?.let { context ->
            currentActivity?.let { activity ->
                // Check if SmackSdk is already initialized
                if (smackSdk == null) {
                    val smackClient = DefaultSmackClient(AndroidSmackLogger())
                    nfcAdapterWrapper = AndroidNfcAdapterWrapper()
                    smackSdk =
                            SmackSdk.Builder(smackClient)
                                    .setNfcAdapterWrapper(nfcAdapterWrapper!!)
                                    .setCoroutineDispatcher(Dispatchers.IO)
                                    .build()
                    (activity as? FragmentActivity)?.let { fragmentActivity ->
                        smackSdk!!.onCreate(fragmentActivity)
                        Log.d(TAG, "SmackSdk onCreate called.")
                    } ?: run {
                        Log.e(
                                TAG,
                                "Activity is not a FragmentActivity, cannot initialize SmackSdk onCreate."
                        )
                    }
                } else {
                    Log.d(TAG, "SmackSdk already initialized.")
                }

                // Check if registrationViewModel is already initialized
                if (registrationViewModel == null && activity is ViewModelStoreOwner) {
                    registrationViewModel =
                            ViewModelProvider(activity, RegistrationViewModelFactory(smackSdk!!))
                                    .get(RegistrationViewModel::class.java)

                    if (activity is androidx.lifecycle.LifecycleOwner) {
                        registrationViewModel!!.setupResult.observe(activity) { success ->
                            Log.d(TAG, "setupResult: $success")
                            channel.invokeMethod("setupResult", success)
                        }
                    }
                    Log.d(TAG, "RegistrationViewModel initialized.")
                } else if (registrationViewModel != null) {
                    Log.d(TAG, "RegistrationViewModel already initialized.")
                }


                if (smackSdk != null && registrationViewModel != null && !isPluginInitialized) {
                    isPluginInitialized = true
                    currentActivity?.runOnUiThread {
                        channel.invokeMethod("pluginInitialized", true)
                        Log.d(TAG, "pluginInitialized event sent to Flutter.")
                    }
                }
            } ?: run {
                Log.e(TAG, "Current activity is null in initializeSmackAndViewModel.")
            }
        } ?: run {
            Log.e(TAG, "Application context is null in initializeSmackAndViewModel.")
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "onDetachedFromActivityForConfigChanges")
        currentActivity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        currentActivity = binding.activity
        binding.addOnNewIntentListener { intent ->
            handleNewIntent(intent)
            true 
        }
        Log.d(TAG, "onReattachedToActivityForConfigChanges: Activity reattached.")
        initializeSmackAndViewModel() 
    }

    override fun onDetachedFromActivity() {
        Log.d(TAG, "onDetachedFromActivity: Activity detached.")
        currentActivity = null
        smackSdk = null 
        nfcAdapterWrapper = null
        registrationViewModel = null
        isPluginInitialized = false 
    }

    private fun handleNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent called in plugin for intent: ${intent.action}")
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {

            smackSdk?.onNewIntent(intent) 

            val tag: Tag? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                    }

            isLockPresent = tag != null
            Log.d(TAG, "Tag detected? $isLockPresent")
            channel.invokeMethod("lockPresent", isLockPresent)

        } else {
            Log.d(TAG, "Unhandled intent action: ${intent.action}")
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (!isPluginInitialized && call.method != "getPlatformVersion") { 
             Log.e(TAG, "Plugin not initialized yet. Cannot process method call: ${call.method}")
             result.error(
                 "NOT_INITIALIZED",
                 "InfineonNfcLockControlPlugin is not ready. Please ensure activity is attached and NFC is enabled.",
                 null
             )
             return
        }

        // Handle "lockPresent" immediately as it's a simple state check
        if (call.method == "lockPresent") {
            result.success(isLockPresent)
            return
        }

        val currentViewModel = registrationViewModel
        if (currentViewModel == null) {
            // This case should ideally be caught by isPluginInitialized check now, but good fallback
            Log.e(
                    TAG,
                    "registrationViewModel not initialized yet. Cannot process method call: ${call.method}"
            )
            result.error(
                    "NOT_INITIALIZED",
                    "registrationViewModel is not ready. Please ensure NFC is enabled and the app is in the foreground.",
                    null
            )
            return
        }

        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "setupNewLock" -> {
                val supervisorKey = call.argument<String>("supervisorKey") ?: ""
                val newPassword = call.argument<String>("newPassword") ?: ""
                val userName = call.argument<String>("userName") ?: ""

                currentViewModel.setupNewLock(
                        userName,
                        supervisorKey,
                        newPassword,
                        onComplete = { success ->
                            currentActivity?.runOnUiThread { result.success(success) }
                        }
                )
            }
            "changePassword" -> {
                val supervisorKey = call.argument<String>("supervisorKey") ?: ""
                val newPassword = call.argument<String>("newPassword") ?: ""
                val userName = call.argument<String>("userName") ?: ""

                currentViewModel.changePassword(
                        userName,
                        supervisorKey,
                        newPassword,
                        onComplete = { success ->
                            currentActivity?.runOnUiThread { result.success(success) }
                        }
                )
            }
            "unlockLock" -> {
                val password = call.argument<String>("password") ?: ""
                val userName = call.argument<String>("userName") ?: ""

                currentViewModel.unlockLock(
                        userName,
                        password,
                        onComplete = { success ->
                            currentActivity?.runOnUiThread { result.success(success) }
                        }
                )
            }
            "lockLock" -> {
                val password = call.argument<String>("password") ?: ""
                val userName = call.argument<String>("userName") ?: ""

                currentViewModel.lockLock(
                        userName,
                        password,
                        onComplete = { success ->
                            currentActivity?.runOnUiThread { result.success(success) }
                        }
                )
            }
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(TAG, "onDetachedFromEngine")
        channel.setMethodCallHandler(null)
        applicationContext = null
    }
}

class RegistrationViewModel(private val smackSdk: SmackSdk) : ViewModel() {
    val setupResult = MutableLiveData<Boolean>() 

    fun setupNewLock(
            userName: String,
            supervisorKey: String,
            newPassword: String,
            onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                smackSdk.lockApi
                        .getLock()
                        .retry { e -> e !is CancellationException }
                        .filterNotNull()
                        .take(1)
                        .collect { lock ->
                            val key =
                                    if (lock.isNew) {
                                        smackSdk.lockApi.setLockKey(
                                                lock,
                                                userName,
                                                LocalDateTime.now().toEpochSecond(ZoneOffset.UTC),
                                                supervisorKey,
                                                newPassword
                                        )
                                    } else {
                                        // New password is used to validate existing lock
                                        smackSdk.lockApi.validatePassword(
                                                lock,
                                                userName,
                                                System.currentTimeMillis() / 1000,
                                                newPassword
                                        )
                                    }
                            // Initialize session and unlock with the obtained key
                            smackSdk.lockApi.initializeSession(
                                    lock,
                                    userName,
                                    System.currentTimeMillis() / 1000,
                                    key
                            )
                            smackSdk.lockApi.unlock(lock, key)

                            // Emit success and abort further collection
                            onComplete(true)
                        }
            } catch (e: CancellationException) {
                Log.e("CancellationException", "Failed to set password", e)
                onComplete(false)
            } catch (e: Exception) {
                Log.e("RegistrationViewModel", "setupNewLock failed", e)
                onComplete(false)
            }
        }
    }

    fun changePassword(
            userName: String,
            supervisorKey: String,
            newPassword: String,
            onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                smackSdk.lockApi
                        .getLock()
                        .retry { e -> e !is CancellationException }
                        .filterNotNull()
                        .take(1)
                        .collect { lock ->
                            val timestamp = System.currentTimeMillis() / 1000

                            smackSdk.lockApi.setLockKey(
                                    lock,
                                    userName,
                                    timestamp,
                                    supervisorKey,
                                    newPassword
                            )
                            onComplete(true)
                        }
            } catch (e: CancellationException) {
                Log.e("CancellationException", "Failed to change password", e)
                onComplete(false)
            } catch (e: Exception) {
                Log.e("RegistrationViewModel", "Failed to change password", e)
                onComplete(false)
            }
        }
    }

    fun unlockLock(userName: String, password: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("RegistrationViewModel", "starting unlock")

                smackSdk.lockApi
                        .getLock()
                        .retry { e -> e !is CancellationException }
                        .filterNotNull()
                        .take(1)
                        .collect { lock ->
                            val timestamp = System.currentTimeMillis() / 1000
                            Log.d("RegistrationViewModel", "lock goted")

                            val key =
                                    smackSdk.lockApi.validatePassword(
                                            lock,
                                            userName,
                                            timestamp,
                                            password
                                    )
                            smackSdk.lockApi.initializeSession(
                                    lock,
                                    userName,
                                    timestamp,
                                    key,
                            )
                            smackSdk.lockApi.unlock(lock, key)
                            onComplete(true)
                        }
            } catch (e: CancellationException) {
                Log.d("CancellationException", "Unlock cancelled", e)
                onComplete(false)
            } catch (e: Exception) {
                Log.e("RegistrationViewModel", "unlockLock failed", e)
                onComplete(false)
            }
        }
    }

    fun lockLock(userName: String, password: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d("RegistrationViewModel", "starting lock")

                smackSdk.lockApi
                        .getLock()
                        .retry { e -> e !is CancellationException }
                        .filterNotNull()
                        .take(1)
                        .collect { lock ->
                            val timestamp = System.currentTimeMillis() / 1000
                            val key =
                                    smackSdk.lockApi.validatePassword(
                                            lock,
                                            userName,
                                            timestamp,
                                            password
                                    )
                            smackSdk.lockApi.initializeSession(lock, userName, timestamp, key)

                            smackSdk.lockApi.lock(lock, key)
                            onComplete(true)
                        }
            } catch (e: CancellationException) {
                onComplete(false)
            } catch (e: Exception) {
                Log.e("RegistrationViewModel", "lock failed", e)
                onComplete(false)
            }
        }
    }
}

class RegistrationViewModelFactory(private val smackSdk: SmackSdk) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RegistrationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST") return RegistrationViewModel(smackSdk) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${'$'}{modelClass.name}")
    }
}