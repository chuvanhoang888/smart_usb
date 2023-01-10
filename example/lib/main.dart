// ignore_for_file: non_constant_identifier_names
import 'package:esc_pos_utils/esc_pos_utils.dart';
import 'package:flutter/material.dart';
import 'package:smart_usb/smart_usb.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  List<UsbDeviceDescription> _deviceList = [];
  @override
  void initState() {
    // TODO: implement initState
    super.initState();
    SmartUsb.init();
    _scan();
  }

  void _scan() async {
    _deviceList.clear();
    var descriptions =
        await SmartUsb.getDevicesWithDescription(requestPermission: false);
    print(descriptions);
    _deviceList = descriptions;
    setState(() {});
  }

  Future<bool> connectDevice(UsbDeviceDescription device) async {
    var isConnect = await SmartUsb.connectDevice(device.device);
    print(isConnect);
    return isConnect;
  }

  Future _printReceiveTest(UsbDeviceDescription device) async {
    List<int> bytes = [];

    // Xprinter XP-N160I
    final profile = await CapabilityProfile.load(name: 'XP-N160I');
    // PaperSize.mm80 or PaperSize.mm58
    final generator = Generator(PaperSize.mm80, profile);
    bytes += generator.setGlobalCodeTable('CP1252');
    bytes += generator.text('Test Print',
        styles: const PosStyles(align: PosAlign.center));
    bytes += generator.cut();
    await SmartUsb.send(bytes);
  }

  Future _connectWithPrint(UsbDeviceDescription device) async {
    List<int> bytes = [];

    // Xprinter XP-N160I
    final profile = await CapabilityProfile.load(name: 'XP-N160I');
    // PaperSize.mm80 or PaperSize.mm58
    final generator = Generator(PaperSize.mm80, profile);
    bytes += generator.setGlobalCodeTable('CP1252');
    bytes += generator.text('Test Print',
        styles: const PosStyles(align: PosAlign.center));

    _printEscPos(device, bytes, generator);
  }

  /// print ticket
  void _printEscPos(
      UsbDeviceDescription device, List<int> bytes, Generator generator) async {
    //bytes += generator.feed(2);
    bytes += generator.cut();
    var isConnect = await SmartUsb.connectDevice(device.device);
    print(isConnect);
    if (isConnect) {
      await SmartUsb.send(bytes);
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
        home: DefaultTabController(
            length: 2,
            child: Scaffold(
                appBar: AppBar(
                  title: const Text('Flutter Smart Usb example app'),
                  bottom: const TabBar(
                    tabs: [
                      Tab(icon: Icon(Icons.home_max)),
                      Tab(icon: Icon(Icons.connect_without_contact_rounded)),
                    ],
                  ),
                ),
                floatingActionButton: FloatingActionButton(
                  onPressed: () {
                    _scan();
                  },
                  child: const Icon(Icons.refresh),
                ),
                body: TabBarView(children: [
                  Center(
                      child: Container(
                    height: double.infinity,
                    constraints: const BoxConstraints(maxWidth: 400),
                    child: SingleChildScrollView(
                      padding: EdgeInsets.zero,
                      child: Column(
                        children: [
                          Column(
                              children: _deviceList
                                  .map(
                                    (device) => ListTile(
                                      title: Text('${device.product}'),
                                      subtitle:
                                          Text("${device.device.vendorId}"),
                                      onTap: () {
                                        // do something
                                      },
                                      trailing: OutlinedButton(
                                        onPressed: () async {
                                          _connectWithPrint(device);
                                        },
                                        child: const Padding(
                                          padding: EdgeInsets.symmetric(
                                              vertical: 2, horizontal: 20),
                                          child: Text("Print test ticket",
                                              textAlign: TextAlign.center),
                                        ),
                                      ),
                                    ),
                                  )
                                  .toList()),
                        ],
                      ),
                    ),
                  )),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          SingleChildScrollView(
                            padding: EdgeInsets.zero,
                            child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: _deviceList
                                    .map(
                                      (device) => Row(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.center,
                                        children: [
                                          Column(
                                            crossAxisAlignment:
                                                CrossAxisAlignment.start,
                                            children: [
                                              Text('${device.product}'),
                                              Text("${device.device.vendorId}"),
                                            ],
                                          ),
                                          const SizedBox(
                                            width: 10,
                                          ),
                                          OutlinedButton(
                                            onPressed: () async {
                                              connectDevice(device);
                                            },
                                            child: const Padding(
                                              padding: EdgeInsets.symmetric(
                                                  vertical: 2, horizontal: 20),
                                              child: Text("Connect Printer",
                                                  textAlign: TextAlign.center),
                                            ),
                                          ),
                                          const SizedBox(
                                            width: 10,
                                          ),
                                          OutlinedButton(
                                            onPressed: () async {
                                              _printReceiveTest(device);
                                            },
                                            child: const Padding(
                                              padding: EdgeInsets.symmetric(
                                                  vertical: 2, horizontal: 20),
                                              child: Text("Print test ticket",
                                                  textAlign: TextAlign.center),
                                            ),
                                          ),
                                        ],
                                      ),
                                    )
                                    .toList()),
                          ),
                        ],
                      ),
                    ],
                  ),
                ]))));
  }

  void log(String info) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(info)));
  }
}
