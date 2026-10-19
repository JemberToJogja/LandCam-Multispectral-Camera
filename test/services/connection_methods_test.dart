import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:landcam/models/landcam_models.dart';
import 'package:landcam/services/native_bridge.dart';
import 'package:landcam/services/qr_pairing_service.dart';
import 'package:landcam/viewmodels/landcam_view_model.dart';

void main() {
  const qrPairingService = QrPairingService();

  group('CameraPairingMethod', () {
    test('provides stable native identifiers for every pairing method', () {
      expect(CameraPairingMethod.nfc.nativeName, 'NFC');
      expect(CameraPairingMethod.qrCode.nativeName, 'QR_CODE');
      expect(CameraPairingMethod.manualWifi.nativeName, 'WIFI_MANUAL');
      expect(CameraPairingMethod.savedWifi.nativeName, 'WIFI_SAVED');
    });

    test('exposes the correct hardware/input requirements', () {
      expect(CameraPairingMethod.nfc.requiresNfc, isTrue);
      expect(CameraPairingMethod.nfc.requiresQrScanner, isFalse);
      expect(CameraPairingMethod.nfc.requiresManualCredentials, isFalse);

      expect(CameraPairingMethod.qrCode.requiresNfc, isFalse);
      expect(CameraPairingMethod.qrCode.requiresQrScanner, isTrue);
      expect(CameraPairingMethod.qrCode.requiresManualCredentials, isFalse);

      expect(CameraPairingMethod.manualWifi.requiresNfc, isFalse);
      expect(CameraPairingMethod.manualWifi.requiresQrScanner, isFalse);
      expect(CameraPairingMethod.manualWifi.requiresManualCredentials, isTrue);

      expect(CameraPairingMethod.savedWifi.requiresNfc, isFalse);
      expect(CameraPairingMethod.savedWifi.requiresQrScanner, isFalse);
      expect(CameraPairingMethod.savedWifi.requiresManualCredentials, isFalse);
    });
  });

  group('CameraTransportType', () {
    test('provides stable native identifiers', () {
      expect(CameraTransportType.wifi.nativeName, 'WIFI');
      expect(CameraTransportType.bluetooth.nativeName, 'BLUETOOTH');
      expect(CameraTransportType.usbPtp.nativeName, 'USB_PTP');
      expect(CameraTransportType.hdmiUvc.nativeName, 'HDMI_UVC');
    });

    test('distinguishes network, USB, Bluetooth, and video-only transports', () {
      expect(CameraTransportType.wifi.isNetworkTransport, isTrue);
      expect(CameraTransportType.wifi.requiresUsbHost, isFalse);
      expect(CameraTransportType.wifi.isVideoInputOnly, isFalse);
      expect(CameraTransportType.wifi.requiresBluetoothPermission, isFalse);

      expect(CameraTransportType.bluetooth.isNetworkTransport, isFalse);
      expect(CameraTransportType.bluetooth.requiresUsbHost, isFalse);
      expect(CameraTransportType.bluetooth.isVideoInputOnly, isFalse);
      expect(CameraTransportType.bluetooth.requiresBluetoothPermission, isTrue);

      expect(CameraTransportType.usbPtp.isNetworkTransport, isFalse);
      expect(CameraTransportType.usbPtp.requiresUsbHost, isTrue);
      expect(CameraTransportType.usbPtp.isVideoInputOnly, isFalse);

      expect(CameraTransportType.hdmiUvc.isNetworkTransport, isFalse);
      expect(CameraTransportType.hdmiUvc.requiresUsbHost, isTrue);
      expect(CameraTransportType.hdmiUvc.isVideoInputOnly, isTrue);
    });
  });

  group('CameraTransportStatus', () {
    test('marks in-progress states as busy', () {
      const busyStates = <CameraTransportStatus>{
        CameraTransportStatus.checkingPermission,
        CameraTransportStatus.connecting,
        CameraTransportStatus.discovering,
        CameraTransportStatus.validating,
        CameraTransportStatus.reconnecting,
      };

      for (final status in CameraTransportStatus.values) {
        expect(
          status.isBusy,
          busyStates.contains(status),
          reason: '${status.name} has an unexpected busy state',
        );
      }
    });

    test('only connected and streaming count as connected', () {
      expect(CameraTransportStatus.connected.isConnected, isTrue);
      expect(CameraTransportStatus.streaming.isConnected, isTrue);

      for (final status in CameraTransportStatus.values.where(
        (value) =>
            value != CameraTransportStatus.connected &&
            value != CameraTransportStatus.streaming,
      )) {
        expect(
          status.isConnected,
          isFalse,
          reason: '${status.name} must not be reported as connected',
        );
      }
    });

    test('reports the error state explicitly', () {
      expect(CameraTransportStatus.error.hasError, isTrue);
      expect(CameraTransportStatus.disconnected.hasError, isFalse);
      expect(CameraTransportStatus.connecting.hasError, isFalse);
    });
  });

  group('CameraTransportCapabilities', () {
    test('requires Red, NIR, and an aligned pair before enabling NDVI', () {
      const complete = CameraTransportCapabilities(
        hasRedChannel: true,
        hasNirChannel: true,
        hasAlignedRedNirPair: true,
      );
      expect(complete.canCalculateNdvi, isTrue);

      const missingNir = CameraTransportCapabilities(
        hasRedChannel: true,
        hasNirChannel: false,
        hasAlignedRedNirPair: true,
      );
      expect(missingNir.canCalculateNdvi, isFalse);

      const unaligned = CameraTransportCapabilities(
        hasRedChannel: true,
        hasNirChannel: true,
        hasAlignedRedNirPair: false,
      );
      expect(unaligned.canCalculateNdvi, isFalse);
    });

    test('round-trips capability flags through a map', () {
      const original = CameraTransportCapabilities(
        canControlCamera: true,
        canReceiveLiveView: true,
        canCapturePhoto: true,
        canTransferFiles: false,
        hasRedChannel: true,
        hasNirChannel: true,
        hasAlignedRedNirPair: true,
      );

      final encoded = original.toMap();
      final decoded = CameraTransportCapabilities.fromMap(encoded);

      expect(decoded.canControlCamera, isTrue);
      expect(decoded.canReceiveLiveView, isTrue);
      expect(decoded.canCapturePhoto, isTrue);
      expect(decoded.canTransferFiles, isFalse);
      expect(decoded.hasRedChannel, isTrue);
      expect(decoded.hasNirChannel, isTrue);
      expect(decoded.hasAlignedRedNirPair, isTrue);
      expect(decoded.canCalculateNdvi, isTrue);
      expect(encoded['canCalculateNdvi'], isTrue);
    });

    test('missing or non-boolean capability fields default to false', () {
      final decoded = CameraTransportCapabilities.fromMap(<String, dynamic>{
        'canControlCamera': 'true',
        'canReceiveLiveView': 1,
        'hasNirChannel': null,
      });

      expect(decoded.canControlCamera, isFalse);
      expect(decoded.canReceiveLiveView, isFalse);
      expect(decoded.canCapturePhoto, isFalse);
      expect(decoded.hasRedChannel, isFalse);
      expect(decoded.hasNirChannel, isFalse);
      expect(decoded.hasAlignedRedNirPair, isFalse);
      expect(decoded.canCalculateNdvi, isFalse);
    });
  });

  group('QrPairingService.parsePayload', () {
    test('parses a standard WPA Wi-Fi QR payload', () {
      final result = qrPairingService.parsePayload(
        'WIFI:T:WPA;S:DIRECT-ABCD:ILCE-6400;P:12345678;;',
      );

      expect(result.isSuccess, isTrue);
      expect(result.failure, QrPairingFailure.none);
      expect(result.format, QrPairingFormat.wifiQr);
      expect(result.credentials?.ssid, 'DIRECT-ABCD:ILCE-6400');
      expect(result.credentials?.password, '12345678');
      expect(result.credentials?.securityType, 'WPA2');
      expect(result.credentials?.isOpenNetwork, isFalse);
    });

    test('trims the payload and accepts an escaped semicolon in an SSID', () {
      final result = qrPairingService.parsePayload(
        r'  WIFI:T:WPA;S:LANDCAM\;TEST;P:12345678;;  ',
      );

      expect(result.isSuccess, isTrue);
      expect(result.credentials?.ssid, 'LANDCAM;TEST');
    });

    test('parses an open Wi-Fi QR without requiring a password', () {
      final result = qrPairingService.parsePayload(
        'WIFI:T:nopass;S:LANDCAM-OPEN;;',
      );

      expect(result.isSuccess, isTrue);
      expect(result.credentials?.ssid, 'LANDCAM-OPEN');
      expect(result.credentials?.password, isEmpty);
      expect(result.credentials?.securityType, 'NONE');
      expect(result.credentials?.isOpenNetwork, isTrue);
    });

    test('rejects an open network QR that also supplies a password', () {
      final result = qrPairingService.parsePayload(
        'WIFI:T:nopass;S:LANDCAM-OPEN;P:12345678;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.invalidPassword);
    });

    test('parses JSON credentials and accepts the supported key aliases', () {
      final result = qrPairingService.parsePayload(
        '{"ssidName":"DIRECT-ABCD:ILCE-6400",'
        '"psk":12345678,"auth":"WPA2"}',
      );

      expect(result.isSuccess, isTrue);
      expect(result.format, QrPairingFormat.json);
      expect(result.credentials?.ssid, 'DIRECT-ABCD:ILCE-6400');
      expect(result.credentials?.password, '12345678');
      expect(result.credentials?.securityType, 'WPA2');
    });

    test('rejects JSON credential payloads with no SSID', () {
      final result = qrPairingService.parsePayload(
        '{"password":"12345678","securityType":"WPA2"}',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.missingSsid);
      expect(result.format, QrPairingFormat.json);
    });

    test('parses readable Sony text when an explicit password is present', () {
      final result = qrPairingService.parsePayload(
        'SSID=DIRECT-ABCD:ILCE-6400; password=AB12CD34',
      );

      expect(result.isSuccess, isTrue);
      expect(result.format, QrPairingFormat.sonyText);
      expect(result.credentials?.ssid, 'DIRECT-ABCD:ILCE-6400');
      expect(result.credentials?.password, 'AB12CD34');
      expect(result.credentials?.securityType, 'WPA2');
    });

    test('supports the documented eight-digit Sony text password fallback', () {
      final result = qrPairingService.parsePayload(
        'Camera DIRECT-ABCD:ILCE-6400 12345678',
      );

      expect(result.isSuccess, isTrue);
      expect(result.credentials?.password, '12345678');
    });

    test('returns missingPassword for readable Sony text without a password', () {
      final result = qrPairingService.parsePayload(
        'Camera SSID DIRECT-ABCD:ILCE-6400',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.missingPassword);
      expect(result.format, QrPairingFormat.sonyText);
    });

    test('rejects null and whitespace-only payloads', () {
      for (final payload in <String?>[null, '', '   \n\t']) {
        final result = qrPairingService.parsePayload(payload);
        expect(result.isSuccess, isFalse);
        expect(result.failure, QrPairingFailure.emptyPayload);
      }
    });

    test('rejects unsupported Wi-Fi security types', () {
      final result = qrPairingService.parsePayload(
        'WIFI:T:WEP;S:DIRECT-ABCD:ILCE-6400;P:12345678;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.unsupportedSecurity);
      expect(result.format, QrPairingFormat.wifiQr);
    });

    test('rejects a Wi-Fi QR with no SSID', () {
      final result = qrPairingService.parsePayload(
        'WIFI:T:WPA;P:12345678;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.missingSsid);
    });

    test('rejects a Wi-Fi QR with no security declaration', () {
      final result = qrPairingService.parsePayload(
        'WIFI:S:DIRECT-ABCD:ILCE-6400;P:12345678;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.unsupportedSecurity);
    });

    test('rejects an SSID longer than 32 UTF-8 bytes', () {
      final longSsid = List<String>.filled(33, 'A').join();
      final result = qrPairingService.parsePayload(
        'WIFI:T:WPA;S:$longSsid;P:12345678;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.invalidSsid);
    });

    test('rejects WPA passwords shorter than eight characters', () {
      final result = qrPairingService.parsePayload(
        'WIFI:T:WPA;S:DIRECT-ABCD:ILCE-6400;P:1234567;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.invalidPassword);
    });

    test('rejects WPA passwords longer than 63 characters', () {
      final longPassword = List<String>.filled(64, 'A').join();
      final result = qrPairingService.parsePayload(
        'WIFI:T:WPA;S:DIRECT-ABCD:ILCE-6400;P:$longPassword;;',
      );

      expect(result.isSuccess, isFalse);
      expect(result.failure, QrPairingFailure.invalidPassword);
    });

    test('does not interpret unrelated JSON or text as camera credentials', () {
      final unrelatedJson = qrPairingService.parsePayload(
        '{"device":"camera","id":42}',
      );
      final unrelatedText = qrPairingService.parsePayload('hello world');

      expect(unrelatedJson.isSuccess, isFalse);
      expect(unrelatedJson.failure, QrPairingFailure.unsupportedFormat);
      expect(unrelatedText.isSuccess, isFalse);
      expect(unrelatedText.failure, QrPairingFailure.unsupportedFormat);
    });

    test('does not expose the Wi-Fi password through diagnostic strings', () {
      const secret = 'Sensitive123';
      const credentials = CameraWifiCredentials(
        ssid: 'DIRECT-ABCD:ILCE-6400',
        password: secret,
        securityType: 'WPA2',
      );
      final result = QrPairingResult.success(
        credentials: credentials,
        format: QrPairingFormat.json,
      );

      expect(credentials.toString(), isNot(contains(secret)));
      expect(result.toString(), isNot(contains(secret)));
      expect(credentials.toString(), contains('[REDACTED]'));
    });
  });

  group('LandCamViewModel connection methods', () {
    late List<MethodCall> nativeCalls;

    setUpAll(() {
      TestWidgetsFlutterBinding.ensureInitialized();
    });

    setUp(() {
      nativeCalls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        NativeBridge.methods,
        (MethodCall call) async {
          nativeCalls.add(call);

          if (call.method == 'scanBluetoothDevices') {
            return <Map<String, Object?>>[
              <String, Object?>{'id': 'bt-1', 'name': 'Test Camera'},
            ];
          }
          if (call.method == 'scanUsbDevices') {
            return <Map<String, Object?>>[
              <String, Object?>{
                'deviceId': 7,
                'vendorId': 1356,
                'productId': 1234,
              },
            ];
          }
          return true;
        },
      );
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(NativeBridge.methods, null);
    });

    test('manual Wi-Fi forwards validated credentials to native', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final accepted = await viewModel.connectWifiManually(
        ssid: 'DIRECT-ABCD:ILCE-6400',
        password: '12345678',
      );

      expect(accepted, isTrue);
      expect(nativeCalls, hasLength(1));
      expect(nativeCalls.single.method, 'connectWifi');
      final arguments = nativeCalls.single.arguments as Map<dynamic, dynamic>;
      expect(arguments['ssid'], 'DIRECT-ABCD:ILCE-6400');
      expect(arguments['password'], '12345678');
      expect(arguments['securityType'], 'WPA2');
      expect(arguments['pairingMethod'], 'WIFI_MANUAL');
      expect(viewModel.transport, CameraTransportType.wifi);
      expect(viewModel.transportStatus, CameraTransportStatus.connecting);
    });

    test('manual Wi-Fi rejects invalid credentials before calling native', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final emptySsid = await viewModel.connectWifiManually(
        ssid: '  ',
        password: '12345678',
      );
      final shortPassword = await viewModel.connectWifiManually(
        ssid: 'DIRECT-ABCD:ILCE-6400',
        password: 'short',
      );
      final longSsid = await viewModel.connectWifiManually(
        ssid: List<String>.filled(33, 'A').join(),
        password: '12345678',
      );

      expect(emptySsid, isFalse);
      expect(shortPassword, isFalse);
      expect(longSsid, isFalse);
      expect(nativeCalls, isEmpty);
    });

    test('QR pairing forwards parsed credentials with the QR method identifier',
        () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);
      final result = qrPairingService.parsePayload(
        'WIFI:T:WPA;S:DIRECT-ABCD:ILCE-6400;P:12345678;;',
      );

      final accepted = await viewModel.connectWifiFromQrResult(result);

      expect(accepted, isTrue);
      expect(nativeCalls, hasLength(1));
      expect(nativeCalls.single.method, 'connectWifi');
      final arguments = nativeCalls.single.arguments as Map<dynamic, dynamic>;
      expect(arguments['ssid'], 'DIRECT-ABCD:ILCE-6400');
      expect(arguments['password'], '12345678');
      expect(arguments['securityType'], 'WPA2');
      expect(arguments['pairingMethod'], 'QR_CODE');
    });

    test('invalid QR result never starts a native connection', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);
      final result = qrPairingService.parsePayload('not a camera QR code');

      final accepted = await viewModel.connectWifiFromQrResult(result);

      expect(accepted, isFalse);
      expect(nativeCalls, isEmpty);
      expect(viewModel.logsText, contains('QR pairing result is not valid'));
    });

    test('Bluetooth connection forwards the selected device identifier', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final accepted = await viewModel.connectBluetooth(deviceId: 'bt-1');

      expect(accepted, isTrue);
      expect(nativeCalls.single.method, 'connectBluetooth');
      expect(
        (nativeCalls.single.arguments as Map<dynamic, dynamic>)['deviceId'],
        'bt-1',
      );
      expect(viewModel.transport, CameraTransportType.bluetooth);
    });

    test('USB PTP connection forwards device and USB identifiers', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final accepted = await viewModel.connectUsbPtp(
        deviceId: 7,
        vendorId: 1356,
        productId: 1234,
      );

      expect(accepted, isTrue);
      expect(nativeCalls.single.method, 'connectUsbPtp');
      final arguments = nativeCalls.single.arguments as Map<dynamic, dynamic>;
      expect(arguments['deviceId'], 7);
      expect(arguments['vendorId'], 1356);
      expect(arguments['productId'], 1234);
      expect(viewModel.transport, CameraTransportType.usbPtp);
    });

    test('HDMI/UVC connection forwards the selected capture-device identifier',
        () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final accepted = await viewModel.connectHdmiUvc(deviceId: 'uvc-0');

      expect(accepted, isTrue);
      expect(nativeCalls.single.method, 'connectHdmiUvc');
      expect(
        (nativeCalls.single.arguments as Map<dynamic, dynamic>)['deviceId'],
        'uvc-0',
      );
      expect(viewModel.transport, CameraTransportType.hdmiUvc);
    });

    test('device scans normalize native lists into typed maps', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final bluetooth = await viewModel.scanBluetoothDevices();
      final usb = await viewModel.scanUsbDevices();

      expect(bluetooth, hasLength(1));
      expect(bluetooth.single['id'], 'bt-1');
      expect(bluetooth.single['name'], 'Test Camera');
      expect(usb, hasLength(1));
      expect(usb.single['deviceId'], 7);
      expect(usb.single['vendorId'], 1356);
      expect(
        nativeCalls.map((call) => call.method),
        containsAll(<String>['scanBluetoothDevices', 'scanUsbDevices']),
      );
    });

    test('USB permission request identifies the USB PTP transport', () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final granted = await viewModel.requestUsbPermission(deviceId: 7);

      expect(granted, isTrue);
      expect(nativeCalls.single.method, 'requestUsbPermission');
      final arguments = nativeCalls.single.arguments as Map<dynamic, dynamic>;
      expect(arguments['deviceId'], 7);
      expect(arguments['transport'], 'USB_PTP');
    });

    test('preferred transport only updates local state after native accepts it',
        () async {
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final accepted = await viewModel.setPreferredTransport(
        CameraTransportType.usbPtp,
      );

      expect(accepted, isTrue);
      expect(nativeCalls.single.method, 'setPreferredTransport');
      expect(
        (nativeCalls.single.arguments as Map<dynamic, dynamic>)['transport'],
        'USB_PTP',
      );
      expect(viewModel.transport, CameraTransportType.usbPtp);
    });

    test('native rejection returns false and clears the active operation', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        NativeBridge.methods,
        (MethodCall call) async {
          nativeCalls.add(call);
          return false;
        },
      );
      final viewModel = LandCamViewModel();
      addTearDown(viewModel.dispose);

      final accepted = await viewModel.connectBluetooth(deviceId: 'bt-1');

      expect(accepted, isFalse);
      expect(viewModel.connectionOperationInFlight, isFalse);
      expect(viewModel.transportStatus, CameraTransportStatus.unsupported);
      expect(viewModel.logsText, contains('connection not accepted'));
    });

    test('does not allow overlapping connection requests', () async {
      final releaseNativeCall = Completer<void>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        NativeBridge.methods,
        (MethodCall call) async {
          nativeCalls.add(call);
          await releaseNativeCall.future;
          return true;
        },
      );
      final viewModel = LandCamViewModel();
      addTearDown(() {
        if (!releaseNativeCall.isCompleted) releaseNativeCall.complete();
        viewModel.dispose();
      });

      final firstRequest = viewModel.connectWifiManually(
        ssid: 'DIRECT-ABCD:ILCE-6400',
        password: '12345678',
      );
      final secondRequest = await viewModel.connectBluetooth(deviceId: 'bt-1');

      expect(secondRequest, isFalse);
      releaseNativeCall.complete();
      expect(await firstRequest, isTrue);
      expect(nativeCalls.map((call) => call.method), <String>['connectWifi']);
    });
  });

}
