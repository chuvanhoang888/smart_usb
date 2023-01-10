package com.example.smart_usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.*
import android.widget.Toast
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.*


private const val ACTION_USB_PERMISSION = "com.example.smart_usb.USB_PERMISSION"

private val pendingIntentFlag =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
  } else {
    PendingIntent.FLAG_UPDATE_CURRENT
  }

private fun pendingPermissionIntent(context: Context) = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), pendingIntentFlag)

/** SmartUsbPlugin */
class SmartUsbPlugin : FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  /// It might because you keep registering BroadcastReceiver. I made that mistake before as a result it returns this error. Make sure BroadcastReceiver only registered once.
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel: MethodChannel
  private var mContext: Context? = null
  private var mUSBManager: UsbManager? = null
  private var mUsbDevice: UsbDevice? = null
  private var mUsbDeviceConnection: UsbDeviceConnection? = null
  private var mUsbInterface: UsbInterface? = null
  private var mEndPoint: UsbEndpoint? = null
  private var mPermissionIndent: PendingIntent? = null
  private val connectScope = CoroutineScope(Dispatchers.IO)
  private var permissionContinuation: Continuation<UsbDevice?>? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "smart_usb")
    channel.setMethodCallHandler(this)
    mContext = flutterPluginBinding.applicationContext
    mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
    mPermissionIndent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
      PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE)
    } else {
        PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), 0)
    }
    val filter = IntentFilter(ACTION_USB_PERMISSION)
    filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
    Log.v(LOG_TAG, "Smart Usb Printer initialized")
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    mUSBManager = null
    mContext = null
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "getDeviceList" -> {
        val manager = mUSBManager ?: return result.error("IllegalState", "usbManager null", null)
        val usbDeviceList = manager.deviceList.entries.map {
          mapOf(
            "identifier" to it.key,
            "vendorId" to it.value.vendorId,
            "productId" to it.value.productId,
            "configurationCount" to it.value.configurationCount,
          )
        }
        result.success(usbDeviceList)
      }
      "getDeviceDescription" -> {
        val context = mContext ?: return result.error("IllegalState", "applicationContext null", null)
        val manager = mUSBManager ?: return result.error("IllegalState", "usbManager null", null)
        val identifier = call.argument<Map<String, Any>>("device")!!["identifier"]!!;
        val device = manager.deviceList[identifier] ?: return result.error("IllegalState", "usbDevice null", null)
        val requestPermission = call.argument<Boolean>("requestPermission")!!;

        val hasPermission = manager.hasPermission(device)
        if (requestPermission && !hasPermission) {
          val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
              context.unregisterReceiver(this)
              val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
              result.success(mapOf(
                "manufacturer" to device.manufacturerName,
                "product" to device.productName,
                "serialNumber" to if (granted) device.serialNumber else null,
              ))
            }
          }
          context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
          manager.requestPermission(device, pendingPermissionIntent(context))
        } else {
          result.success(mapOf(
            "manufacturer" to device.manufacturerName,
            "product" to device.productName,
            "serialNumber" to if (hasPermission) device.serialNumber else null,
          ))
        }
      }
      "hasPermission" -> {
        val manager = mUSBManager ?: return result.error("IllegalState", "usbManager null", null)
        val identifier = call.argument<String>("identifier")
        val device = manager.deviceList[identifier]
        result.success(manager.hasPermission(device))
      }
      "requestPermission" -> {
        val context = mContext ?: return result.error("IllegalState", "applicationContext null", null)
        val manager = mUSBManager ?: return result.error("IllegalState", "usbManager null", null)
        val identifier = call.argument<String>("identifier")
        val device = manager.deviceList[identifier]
        if (manager.hasPermission(device)) {
          result.success(true)
        } else {
          val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
              context.unregisterReceiver(this)
              val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
              val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
              result.success(granted)
            }
          }
          context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
          manager.requestPermission(device, pendingPermissionIntent(context))
        }
      }
      "connectDevice" -> {
        val context = mContext ?: return result.error("IllegalState", "applicationContext null", null)
        val manager = mUSBManager ?: return result.error("IllegalState", "usbManager null", null)
        val vendorId = call.argument<Int>("vendorId")
        val productId = call.argument<Int>("productId")
        if ((mUsbDevice == null) || (mUsbDevice!!.vendorId != vendorId) || (mUsbDevice!!.productId != productId)) {
          connectScope.launch{
            closeConnectionIfExists()
            val usbDevices: List<UsbDevice> = deviceList
            for (device: UsbDevice in usbDevices) {
                if ((device.vendorId == vendorId) && (device.productId == productId)) {
                    Log.v(
                        LOG_TAG,
                        "Request for device: vendor_id: " + device.vendorId + ", product_id: " + device.productId
                    )
                    closeConnectionIfExists()
                    //It might because you keep registering BroadcastReceiver. I made that mistake before as a result it returns this error. Make sure BroadcastReceiver only registered once.
                    val grantedDevice = RequireDevice(manager, device)
                    if (grantedDevice != null){
                        Log.v(LOG_TAG, "Connected")
                        result.success(true)
                    }  
                    else{
                        Log.v(LOG_TAG, "Connection failed.")
                        result.success(false)
                    } 
                }
            }
          }
          
        }else{
          result.success(true)
        }
      }
      "openDevice" -> {
        val manager = mUSBManager ?: return result.error("IllegalState", "usbManager null", null)
        val identifier = call.argument<String>("identifier")
        mUsbDevice = manager.deviceList[identifier]
        mUsbDeviceConnection = manager.openDevice(mUsbDevice)
        result.success(true)
      }
      "closeDevice" -> {
        mUsbDeviceConnection?.close()
        mUsbDeviceConnection = null
        mUsbDevice = null
        result.success(null)
      }
      "getConfiguration" -> {
        val device = mUsbDevice ?: return result.error("IllegalState", "usbDevice null", null)
        val index = call.argument<Int>("index")!!
        val configuration = device.getConfiguration(index)
        val map = configuration.toMap() + ("index" to index)
        result.success(map)
      }
      "setConfiguration" -> {
        val device = mUsbDevice ?: return result.error("IllegalState", "usbDevice null", null)
        val connection = mUsbDeviceConnection ?: return result.error("IllegalState", "usbDeviceConnection null", null)
        val index = call.argument<Int>("index")!!
        val configuration = device.getConfiguration(index)
        result.success(connection.setConfiguration(configuration))
      }
      "claimInterface" -> {
        val device = mUsbDevice ?: return result.error("IllegalState", "usbDevice null", null)
        val connection = mUsbDeviceConnection ?: return result.error("IllegalState", "usbDeviceConnection null", null)
        val id = call.argument<Int>("id")!!
        val alternateSetting = call.argument<Int>("alternateSetting")!!
        val usbInterface = device.findInterface(id, alternateSetting)
        result.success(connection.claimInterface(usbInterface, true))
      }
      "releaseInterface" -> {
        val device = mUsbDevice ?: return result.error("IllegalState", "usbDevice null", null)
        val connection = mUsbDeviceConnection ?: return result.error("IllegalState", "usbDeviceConnection null", null)
        val id = call.argument<Int>("id")!!
        val alternateSetting = call.argument<Int>("alternateSetting")!!
        val usbInterface = device.findInterface(id, alternateSetting)
        result.success(connection.releaseInterface(usbInterface))
      }
      "bulkTransferIn" -> {
        val device = mUsbDevice ?: return result.error("IllegalState", "usbDevice null", null)
        val connection = mUsbDeviceConnection ?: return result.error(
          "IllegalState",
          "usbDeviceConnection null",
          null
        )
        val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
        val maxLength = call.argument<Int>("maxLength")!!
        val endpoint =
          device.findEndpoint(endpointMap["endpointNumber"] as Int, endpointMap["direction"] as Int)
        val timeout = call.argument<Int>("timeout")!!

        // TODO Check [UsbDeviceConnection.bulkTransfer] API >= 28
        require(maxLength <= UsbRequest__MAX_USBFS_BUFFER_SIZE) { "Before 28, a value larger than 16384 bytes would be truncated down to 16384" }
        val buffer = ByteArray(maxLength)
        val actualLength = connection.bulkTransfer(endpoint, buffer, buffer.count(), timeout)
        if (actualLength < 0) {
          result.error("unknown", "bulkTransferIn error", null)
        } else {
          result.success(buffer.take(actualLength))
        }
      }
      "bulkTransferOut" -> {
        val device = mUsbDevice ?: return result.error("IllegalState", "usbDevice null", null)
        val connection = mUsbDeviceConnection ?: return result.error(
          "IllegalState",
          "usbDeviceConnection null",
          null
        )
        val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
        val data = call.argument<ByteArray>("data")!!
        val timeout = call.argument<Int>("timeout")!!
        val endpoint =
          device.findEndpoint(endpointMap["endpointNumber"] as Int, endpointMap["direction"] as Int)

        // TODO Check [UsbDeviceConnection.bulkTransfer] API >= 28
        val dataSplit = data.asList()
          .windowed(UsbRequest__MAX_USBFS_BUFFER_SIZE, UsbRequest__MAX_USBFS_BUFFER_SIZE, true)
          .map { it.toByteArray() }
        var sum: Int? = null
        for (bytes in dataSplit) {
          val actualLength = connection.bulkTransfer(endpoint, bytes, bytes.count(), timeout)
          if (actualLength < 0) break
          sum = (sum ?: 0) + actualLength
        }
        if (sum == null) {
          result.error("unknown", "bulkTransferOut error", null)
        } else {
          result.success(sum)
        }
      }
      "printBytes" -> {
        val bytes = call.argument<ArrayList<Int>>("data")!!
        Log.v(LOG_TAG, "Printing bytes")
        val isConnected = openConnection()
        if (isConnected) {
          if(mEndPoint != null){
            val chunkSize = mEndPoint!!.maxPacketSize
            Log.v(LOG_TAG, "Max Packet Size: $chunkSize")
            Log.v(LOG_TAG, "Connected to device")
            Thread {
              synchronized(printLock) {
                val vectorData: Vector<Byte> = Vector()
                for (i in bytes.indices) {
                    val `val`: Int = bytes[i]
                    vectorData.add(`val`.toByte())
                }
                val temp: Array<Any> = vectorData.toTypedArray()
                val byteData = ByteArray(temp.size)
                for (i in temp.indices) {
                    byteData[i] = temp[i] as Byte
                }
                var b = 0
                if (mUsbDeviceConnection != null) {
                    if (byteData.size > chunkSize) {
                        var chunks: Int = byteData.size / chunkSize
                        if (byteData.size % chunkSize > 0) {
                            ++chunks
                        }
                        for (i in 0 until chunks) {
                            //val buffer: ByteArray = byteData.copyOfRange(i * chunkSize, chunkSize + i * chunkSize)
                            val buffer: ByteArray = Arrays.copyOfRange(byteData, i * chunkSize, chunkSize + i * chunkSize)
                            b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, buffer, chunkSize, 100000)
                        }
                    } else {
                        b = mUsbDeviceConnection!!.bulkTransfer(mEndPoint, byteData, byteData.size, 100000)
                    }
                    Log.i(LOG_TAG, "Return code: $b")
                }
              }
            }.start()
            result.success(true)
          }else{
            result.success(false)
          }  
        } else {
            Log.v(LOG_TAG, "Failed to connected to device")
            result.success(false)
        }
      }
      else -> result.notImplemented()
    }
  }

  private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action
      if ((ACTION_USB_PERMISSION == action)) {
          synchronized(this) {
              val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
              if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                  Log.i(
                      LOG_TAG,
                      "Success get permission for device ${usbDevice?.deviceId}, vendor_id: ${usbDevice?.vendorId} product_id: ${usbDevice?.productId}"
                  )
                  mUsbDevice = usbDevice
                  openConnection()
                  permissionContinuation!!.resume(usbDevice)
              } else {
                  permissionContinuation!!.resume(null)
                  Toast.makeText(context, "User refused to give USB device permission: ${usbDevice?.deviceName}", Toast.LENGTH_LONG).show()
              }
          }
      } else if ((UsbManager.ACTION_USB_DEVICE_DETACHED == action)) {

          if (mUsbDevice != null) {
              Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show()
              closeConnectionIfExists()
          }

      } else if ((UsbManager.ACTION_USB_DEVICE_ATTACHED == action)) {
//                if (mUsbDevice != null) {
//                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show()
//                    closeConnectionIfExists()
//                }
      }
    }
  }

  public suspend fun RequireDevice(manager: UsbManager, device: UsbDevice): UsbDevice? {
    // 多重要求にならないようにする - Tránh nhiều yêu cầu
    if(permissionContinuation != null)
        return null
    // requestPermissionを実行
    val device = suspendCoroutine<UsbDevice?> {
        manager.requestPermission(device, mPermissionIndent)
        permissionContinuation = it
    }
    // continuationを消去
    permissionContinuation = null
    return device
  }

  val deviceList: List<UsbDevice>
    get() {
        if (mUSBManager == null) {
            Toast.makeText(mContext, "USB Manager is not initialized while trying to get devices list", Toast.LENGTH_LONG).show()
            return emptyList()
        }
        return ArrayList(mUSBManager!!.deviceList.values)
    }
  private fun openConnection(): Boolean {
    if (mUsbDevice == null) {
        Log.e(LOG_TAG, "USB Device is not initialized")
        Toast.makeText(mContext, "USB Device is not connect", Toast.LENGTH_LONG).show()
        return false
    }
    if (mUSBManager == null) {
        Log.e(LOG_TAG, "USB Manager is not initialized")
        return false
    }
    if (mUsbDeviceConnection != null) {
        Log.i(LOG_TAG, "USB Connection already connected")
        return true
    }
    val usbInterface = mUsbDevice!!.getInterface(0)
    for (i in 0 until usbInterface.endpointCount) {
        val ep = usbInterface.getEndpoint(i)
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
            if (ep.direction == UsbConstants.USB_DIR_OUT) {
                val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
                if (usbDeviceConnection == null) {
                    Log.e(LOG_TAG, "Failed to open USB Connection")
                    return false
                }
                Toast.makeText(mContext, "Device connected", Toast.LENGTH_SHORT).show()
                return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                    mEndPoint = ep
                    mUsbInterface = usbInterface
                    mUsbDeviceConnection = usbDeviceConnection
                    true
                } else {
                    usbDeviceConnection.close()
                    Log.e(LOG_TAG, "Failed to retrieve usb connection")
                    false
                }
            }
        }
    }
    return true
  }
  fun closeConnectionIfExists() {
    if (mUsbDeviceConnection != null) {
        mUsbDeviceConnection!!.releaseInterface(mUsbInterface)
        mUsbDeviceConnection!!.close()
        mUsbInterface = null
        mEndPoint = null
        mUsbDevice = null
        mUsbDeviceConnection = null
    }
  }
  companion object {
    private const val LOG_TAG = "SMART USB PRINTER"
    private val printLock = Any()
  }
}

fun UsbDevice.findInterface(id: Int, alternateSetting: Int): UsbInterface? {
  for (i in 0..interfaceCount) {
    val usbInterface = getInterface(i)
    if (usbInterface.id == id && usbInterface.alternateSetting == alternateSetting) {
      return usbInterface
    }
  }
  return null
}

fun UsbDevice.findEndpoint(endpointNumber: Int, direction: Int): UsbEndpoint? {
  for (i in 0..interfaceCount) {
    val usbInterface = getInterface(i)
    for (j in 0..usbInterface.endpointCount) {
      val endpoint = usbInterface.getEndpoint(j)
      if (endpoint.endpointNumber == endpointNumber && endpoint.direction == direction) {
        return endpoint
      }
    }
  }
  return null
}

/** [UsbRequest.MAX_USBFS_BUFFER_SIZE] */
val UsbRequest__MAX_USBFS_BUFFER_SIZE = 16384

fun UsbConfiguration.toMap() = mapOf(
  "id" to id,
  "interfaces" to List(interfaceCount) { getInterface(it).toMap() }
)

fun UsbInterface.toMap() = mapOf(
  "id" to id,
  "alternateSetting" to alternateSetting,
  "endpoints" to List(endpointCount) { getEndpoint(it).toMap() }
)

fun UsbEndpoint.toMap() = mapOf(
        "endpointNumber" to endpointNumber,
        "direction" to direction
)
