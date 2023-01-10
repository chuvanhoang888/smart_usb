import 'dart:typed_data';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:smart_usb/src/common.dart';

abstract class SmartUsbPlatform extends PlatformInterface {
  SmartUsbPlatform() : super(token: _token);

  static final Object _token = Object();

  static late SmartUsbPlatform _instance;

  /// The default instance of [PathProviderPlatform] to use.
  ///
  /// Defaults to [MethodChannelPathProvider].
  static SmartUsbPlatform get instance => _instance;

  /// Platform-specific plugins should set this with their own platform-specific
  /// class that extends [PathProviderPlatform] when they register themselves.
  static set instance(SmartUsbPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<bool> init();

  Future<void> exit();

  Future<List<UsbDevice>> getDeviceList();

  Future<List<UsbDeviceDescription>> getDevicesWithDescription(
      {bool requestPermission = true});

  Future<UsbDeviceDescription> getDeviceDescription(UsbDevice usbDevice,
      {bool requestPermission = true});

  Future<bool> hasPermission(UsbDevice usbDevice);

  Future<bool> requestPermission(UsbDevice usbDevice);

  Future<bool> connectDevice(UsbDevice usbDevice);

  Future<bool> openDevice(UsbDevice usbDevice);

  Future<void> closeDevice();

  Future<UsbConfiguration> getConfiguration(int index);

  Future<bool> setConfiguration(UsbConfiguration config);

  Future<bool> claimInterface(UsbInterface intf);

  Future<bool> detachKernelDriver(UsbInterface intf);

  Future<bool> releaseInterface(UsbInterface intf);

  Future<Uint8List> bulkTransferIn(
      UsbEndpoint endpoint, int maxLength, int timeout);

  Future<int> bulkTransferOut(
      UsbEndpoint endpoint, Uint8List data, int timeout);

  Future<bool> send(List<int> data);

  Future<void> setAutoDetachKernelDriver(bool enable);
}
