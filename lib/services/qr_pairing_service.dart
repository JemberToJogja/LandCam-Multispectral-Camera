
import 'dart:convert';

import 'package:mobile_scanner/mobile_scanner.dart';

/// Supported QR payload formats recognized by LANDCAM.
enum QrPairingFormat {
  wifiQr,
  json,
  sonyText,
}

/// Reason why a scanned QR code could not be used for Wi-Fi pairing.
enum QrPairingFailure {
  none,
  noQrCodeDetected,
  emptyPayload,
  unsupportedFormat,
  missingSsid,
  missingPassword,
  invalidSsid,
  invalidPassword,
  unsupportedSecurity,
}

/// Wi-Fi credentials extracted from a recognized QR payload.
///
/// Passwords are intentionally excluded from [toString] so they are not
/// accidentally exposed through diagnostic logs.
class CameraWifiCredentials {
  const CameraWifiCredentials({
    required this.ssid,
    required this.password,
    required this.securityType,
  });

  /// Camera Wi-Fi network name.
  final String ssid;

  /// Wi-Fi password. Empty only when the network is explicitly open.
  final String password;

  /// Normalized security identifier: WPA2 or NONE.
  final String securityType;

  bool get isOpenNetwork => securityType == 'NONE';

  /// Arguments intended for a native connection method.
  ///
  /// Do not print or log this map because it contains the password.
  Map<String, dynamic> toMap() {
    return {
      'ssid': ssid,
      'password': password,
      'securityType': securityType,
    };
  }

  @override
  String toString() {
    return 'CameraWifiCredentials('
        'ssid: $ssid, '
        'securityType: $securityType, '
        'password: [REDACTED])';
  }
}

/// Result of scanning and validating a QR payload.
///
/// A successful parse means credentials were recognized and passed basic
/// validation. It does not mean Android has connected to the camera.
class QrPairingResult {
  const QrPairingResult._({
    required this.failure,
    required this.message,
    this.credentials,
    this.format,
  });

  final QrPairingFailure failure;
  final String message;
  final CameraWifiCredentials? credentials;
  final QrPairingFormat? format;

  bool get isSuccess =>
      failure == QrPairingFailure.none && credentials != null;

  bool get hasError => !isSuccess;

  factory QrPairingResult.success({
    required CameraWifiCredentials credentials,
    required QrPairingFormat format,
  }) {
    return QrPairingResult._(
      failure: QrPairingFailure.none,
      message: 'Kredensial Wi-Fi berhasil dibaca dan divalidasi.',
      credentials: credentials,
      format: format,
    );
  }

  factory QrPairingResult.failure({
    required QrPairingFailure failure,
    required String message,
    QrPairingFormat? format,
  }) {
    return QrPairingResult._(
      failure: failure,
      message: message,
      format: format,
    );
  }

  /// Safe diagnostic representation; never prints the credentials.
  @override
  String toString() {
    return 'QrPairingResult('
        'success: $isSuccess, '
        'failure: $failure, '
        'format: $format)';
  }
}

/// LANDCAM QR pairing service.
///
/// Responsibilities:
/// - Configure a scanner for QR codes.
/// - Read a decoded QR payload.
/// - Recognize supported Wi-Fi credential formats.
/// - Validate SSID, password, and security type.
/// - Return credentials for the ViewModel/native connection flow.
///
/// This service does not connect to Wi-Fi itself. The ViewModel must forward
/// validated credentials to the native connection implementation.
class QrPairingService {
  const QrPairingService();

  static final RegExp _sonySsidPattern = RegExp(
    r'DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_.-]{1,48}',
    caseSensitive: false,
  );

  static final RegExp _explicitPasswordPattern = RegExp(
    r'(?:password|pass|pwd)\s*[:=]\s*([A-Za-z0-9]{8,63})',
    caseSensitive: false,
  );

  static final RegExp _eightDigitPattern = RegExp(
    r'(?<!\d)\d{8}(?!\d)',
  );

  /// Creates a scanner controller that only detects QR codes.
  ///
  /// The caller owns the returned controller and must dispose it when the
  /// scanner widget is removed.
  MobileScannerController createScannerController() {
    return MobileScannerController(
      detectionSpeed: DetectionSpeed.noDuplicates,
      formats: const <BarcodeFormat>[
        BarcodeFormat.qrCode,
      ],
    );
  }

  /// Parses a scanner result from mobile_scanner.
  ///
  /// Call this from MobileScanner.onDetect.
  QrPairingResult parseCapture(BarcodeCapture capture) {
    QrPairingResult? firstFailure;
    var qrCodeFound = false;

    for (final barcode in capture.barcodes) {
      if (barcode.format != BarcodeFormat.qrCode) {
        continue;
      }

      qrCodeFound = true;

      final result = parsePayload(barcode.rawValue);

      if (result.isSuccess) {
        return result;
      }

      firstFailure ??= result;
    }

    if (firstFailure != null) {
      return firstFailure;
    }

    return QrPairingResult.failure(
      failure: qrCodeFound
          ? QrPairingFailure.emptyPayload
          : QrPairingFailure.noQrCodeDetected,
      message: qrCodeFound
          ? 'QR terdeteksi, tetapi payload-nya kosong.'
          : 'Belum ada QR Code yang terdeteksi.',
    );
  }

  /// Parses a decoded QR payload.
  ///
  /// Supported formats:
  ///
  /// 1. Standard Wi-Fi QR:
  ///    WIFI:T:WPA;S:DIRECT-XXXX:ILCE-6400;P:12345678;;
  ///
  /// 2. JSON:
  ///    {"ssid":"DIRECT-XXXX:ILCE-6400","password":"12345678"}
  ///
  /// 3. Sony W01 camera QR:
  ///    W01:S:iuE1;P:xxxxxxxx;C:ILCE-6400;M:7CB8DAE3AE1F;
  ///    S is the DIRECT- SSID suffix, P is the Wi-Fi passphrase, C is the
  ///    camera model, and M is a device identifier. The SSID is reconstructed
  ///    as DIRECT-<S>:<C> unless S already contains a complete DIRECT SSID.
  ///
  /// 4. Readable Sony credential text:
  ///    A text payload containing a DIRECT-... SSID and a recognizable
  ///    password field, or a compatible eight-character password suffix.
  ///
  /// Unrecognized proprietary payloads are not guessed.
  QrPairingResult parsePayload(String? rawValue) {
    if (rawValue == null || rawValue.trim().isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.emptyPayload,
        message: 'Payload QR kosong.',
      );
    }

    final payload = rawValue.trim();

    if (payload.toUpperCase().startsWith('WIFI:')) {
      return _parseWifiQr(payload);
    }

    // Sony α-series cameras (including the ILCE-6400) can emit a compact
    // proprietary payload such as:
    // W01:S:iuE1;P:xxxxxxxx;C:ILCE-6400;M:7CB8DAE3AE1F;
    // Do not send a recognized W01 payload through the generic text parser:
    // that parser intentionally supports legacy human-readable credentials.
    if (payload.toUpperCase().startsWith('W01:')) {
      return _parseSonyW01(payload);
    }

    final jsonResult = _tryParseJson(payload);

    if (jsonResult != null) {
      return jsonResult;
    }

    final sonyResult = _tryParseSonyText(payload);

    if (sonyResult != null) {
      return sonyResult;
    }

    return QrPairingResult.failure(
      failure: QrPairingFailure.unsupportedFormat,
      message:
          'Format QR belum dikenali. LANDCAM mendukung QR Wi-Fi standar, '
          'JSON, teks kredensial Sony, dan format Sony W01. '
          'Jika QR kamera menggunakan format lain, hubungkan melalui SSID '
          'dan password kamera yang ditampilkan di layar kamera.',
    );
  }

  // --------------------------------------------------------------------------
  // Sony W01 QR payload
  // --------------------------------------------------------------------------

  QrPairingResult _parseSonyW01(String payload) {
    // Parse values by splitting each segment at its first colon. This is
    // important because a complete SSID can itself contain a colon.
    final fields = <String, String>{};
    final body = payload.substring(4); // Remove "W01:".

    for (final segment in body.split(';')) {
      if (segment.trim().isEmpty) {
        continue;
      }

      final separator = segment.indexOf(':');
      if (separator <= 0) {
        continue;
      }

      final key = segment.substring(0, separator).trim().toUpperCase();
      final value = segment.substring(separator + 1).trim();
      if (key.isEmpty) {
        continue;
      }

      // Keep the first occurrence. Duplicate fields are malformed and should
      // not silently override an earlier value (especially the password).
      fields.putIfAbsent(key, () => value);
    }

    final ssidPart = fields['S'];
    final password = fields['P'];
    final model = fields['C'];

    if (ssidPart == null || ssidPart.isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingSsid,
        format: QrPairingFormat.sonyText,
        message: 'QR Sony W01 tidak memiliki field S (identitas SSID).',
      );
    }

    if (password == null || password.isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingPassword,
        format: QrPairingFormat.sonyText,
        message:
            'QR Sony W01 terbaca, tetapi field P (password Wi-Fi) tidak ada.',
      );
    }

    final ssid = _buildSonyW01Ssid(
      ssidPart: ssidPart,
      model: model,
    );

    if (ssid == null) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingSsid,
        format: QrPairingFormat.sonyText,
        message:
            'QR Sony W01 terbaca, tetapi SSID tidak dapat disusun. '
            'Pastikan field S dan C (model kamera) tersedia atau S berisi '
            'SSID DIRECT lengkap.',
      );
    }

    return _buildCredentials(
      ssid: ssid,
      password: password,
      // A non-empty Sony W01 P field represents the camera's Wi-Fi
      // passphrase. The existing Android connection flow supports WPA2.
      security: 'WPA2',
      format: QrPairingFormat.sonyText,
    );
  }

  String? _buildSonyW01Ssid({
    required String ssidPart,
    required String? model,
  }) {
    final value = ssidPart.trim();
    if (value.isEmpty) {
      return null;
    }

    // Some scanners/integrations may return a complete SSID in S. Preserve it
    // rather than adding a second DIRECT- prefix or model suffix.
    if (value.toUpperCase().startsWith('DIRECT-')) {
      if (value.contains(':')) {
        return _isValidSonyDirectSsid(value) ? value : null;
      }

      final normalizedModel = model?.trim();
      if (normalizedModel == null || !_isValidSonyModel(normalizedModel)) {
        return null;
      }

      final completeSsid = '$value:$normalizedModel';
      return _isValidSonyDirectSsid(completeSsid) ? completeSsid : null;
    }

    // Sony W01's S field is commonly the four-character suffix, while C holds
    // the model name. For the user's α6400 QR this becomes
    // DIRECT-<S>:ILCE-6400.
    final normalizedModel = model?.trim();
    if (!_isValidSonySsidToken(value) ||
        normalizedModel == null ||
        !_isValidSonyModel(normalizedModel)) {
      return null;
    }

    final completeSsid = 'DIRECT-$value:$normalizedModel';
    return _isValidSonyDirectSsid(completeSsid) ? completeSsid : null;
  }

  bool _isValidSonySsidToken(String value) {
    return RegExp(r'^[A-Za-z0-9_-]{1,24}$').hasMatch(value);
  }

  bool _isValidSonyModel(String value) {
    return RegExp(r'^[A-Za-z0-9_.-]{1,48}$').hasMatch(value);
  }

  bool _isValidSonyDirectSsid(String value) {
    return RegExp(
      r'^DIRECT-[A-Za-z0-9_-]+:[A-Za-z0-9_.-]{1,48}$',
      caseSensitive: false,
    ).hasMatch(value);
  }

  // --------------------------------------------------------------------------
  // Standard Wi-Fi QR
  // --------------------------------------------------------------------------

  QrPairingResult _parseWifiQr(String payload) {
    final data = payload.substring(5);

    final fields = <String, String>{};

    // Supports escaped delimiters such as \; inside an SSID or password.
    final fieldPattern = RegExp(
      r'(?:^|;)([A-Za-z]):((?:\\.|[^;])*)',
    );

    for (final match in fieldPattern.allMatches(data)) {
      final key = match.group(1)?.toUpperCase();
      final rawFieldValue = match.group(2);

      if (key == null || rawFieldValue == null) {
        continue;
      }

      fields[key] = _unescapeWifiValue(rawFieldValue);
    }

    final ssid = fields['S'];
    final password = fields['P'];
    final security = fields['T'];

    if (ssid == null || ssid.trim().isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingSsid,
        format: QrPairingFormat.wifiQr,
        message: 'QR Wi-Fi tidak memiliki SSID.',
      );
    }

    if (security == null || security.trim().isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.unsupportedSecurity,
        format: QrPairingFormat.wifiQr,
        message: 'Jenis keamanan Wi-Fi tidak tercantum pada QR.',
      );
    }

    return _buildCredentials(
      ssid: ssid,
      password: password,
      security: security,
      format: QrPairingFormat.wifiQr,
    );
  }

  String _unescapeWifiValue(String value) {
    return value.replaceAllMapped(
      RegExp(r'\\(.)'),
      (match) => match.group(1) ?? '',
    );
  }

  // --------------------------------------------------------------------------
  // JSON payload
  // --------------------------------------------------------------------------

  QrPairingResult? _tryParseJson(String payload) {
    dynamic decoded;

    try {
      decoded = jsonDecode(payload);
    } on FormatException {
      return null;
    } catch (_) {
      return null;
    }

    if (decoded is! Map) {
      return null;
    }

    final ssid = _readString(
      decoded,
      const <String>[
        'ssid',
        'ssidName',
      ],
    );

    final hasSsidKey = _containsKey(
      decoded,
      const <String>[
        'ssid',
        'ssidName',
      ],
    );

    final hasPasswordKey = _containsKey(
      decoded,
      const <String>[
        'password',
        'passphrase',
        'psk',
        'pass',
      ],
    );

    // Unrelated JSON payloads are not interpreted as camera credentials.
    if (!hasSsidKey && !hasPasswordKey) {
      return null;
    }

    if (ssid == null || ssid.trim().isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingSsid,
        format: QrPairingFormat.json,
        message: 'QR JSON tidak memiliki SSID yang valid.',
      );
    }

    final password = _readString(
      decoded,
      const <String>[
        'password',
        'passphrase',
        'psk',
        'pass',
      ],
      allowNumeric: true,
    );

    final security = _readString(
      decoded,
      const <String>[
        'securityType',
        'security',
        'encryption',
        'auth',
      ],
    );

    return _buildCredentials(
      ssid: ssid,
      password: password,
      // JSON examples commonly contain only ssid/password. When security is
      // omitted, assume WPA2 for a password-protected camera network. Open
      // networks must explicitly declare NONE/open/nopass.
      security: security ?? 'WPA2',
      format: QrPairingFormat.json,
    );
  }

  bool _containsKey(
    Map<dynamic, dynamic> map,
    List<String> candidates,
  ) {
    final normalizedCandidates = candidates
        .map((key) => key.toLowerCase())
        .toSet();

    return map.keys.any(
      (key) => normalizedCandidates.contains(
        key.toString().toLowerCase(),
      ),
    );
  }

  String? _readString(
    Map<dynamic, dynamic> map,
    List<String> candidates, {
    bool allowNumeric = false,
  }) {
    final normalizedCandidates = candidates
        .map((key) => key.toLowerCase())
        .toSet();

    for (final entry in map.entries) {
      if (!normalizedCandidates.contains(
        entry.key.toString().toLowerCase(),
      )) {
        continue;
      }

      final value = entry.value;

      if (value is String) {
        return value;
      }

      if (allowNumeric && value is num) {
        return value.toString();
      }

      return null;
    }

    return null;
  }

  // --------------------------------------------------------------------------
  // Readable Sony credential text
  // --------------------------------------------------------------------------

  QrPairingResult? _tryParseSonyText(String payload) {
    final ssidMatch = _sonySsidPattern.firstMatch(payload);

    if (ssidMatch == null) {
      return null;
    }

    final ssid = ssidMatch.group(0);

    if (ssid == null || ssid.isEmpty) {
      return null;
    }

    String? password;

    final explicitPassword =
        _explicitPasswordPattern.firstMatch(payload);

    if (explicitPassword != null) {
      password = explicitPassword.group(1);
    }

    // Keep compatibility with the credential extraction strategy used by
    // the existing CameraEngine NFC parser.
    if (password == null) {
      final digitMatches = _eightDigitPattern.allMatches(payload);

      for (final match in digitMatches) {
        final candidate = match.group(0);

        if (candidate != null && candidate != ssid) {
          password = candidate;
        }
      }
    }

    if (password == null && payload.length >= 8) {
      final tail = payload.substring(payload.length - 8).trim();

      if (RegExp(r'^[A-Za-z0-9]{8}$').hasMatch(tail)) {
        password = tail;
      }
    }

    if (password == null || password.isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingPassword,
        format: QrPairingFormat.sonyText,
        message:
            'SSID kamera berhasil dikenali, tetapi password tidak ditemukan. '
            'Masukkan password kamera secara manual.',
      );
    }

    return _buildCredentials(
      ssid: ssid,
      password: password,
      security: 'WPA2',
      format: QrPairingFormat.sonyText,
    );
  }

  // --------------------------------------------------------------------------
  // Shared validation
  // --------------------------------------------------------------------------

  QrPairingResult _buildCredentials({
    required String ssid,
    required String? password,
    required String? security,
    required QrPairingFormat format,
  }) {
    final normalizedSsid = ssid.trim();

    if (normalizedSsid.isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingSsid,
        format: format,
        message: 'SSID kamera tidak boleh kosong.',
      );
    }

    // Wi-Fi SSIDs are limited to 32 octets.
    if (utf8.encode(normalizedSsid).length > 32) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.invalidSsid,
        format: format,
        message: 'SSID melebihi batas 32 byte.',
      );
    }

    final normalizedSecurity = (security ?? '').trim().toLowerCase();

    final isOpen = const <String>{
      'nopass',
      'none',
      'open',
      'no password',
    }.contains(normalizedSecurity);

    final isWpa = const <String>{
      'wpa',
      'wpa2',
      'wpa/wpa2',
      'wpa2-psk',
      'psk',
    }.contains(normalizedSecurity);

    if (!isOpen && !isWpa) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.unsupportedSecurity,
        format: format,
        message:
            'Jenis keamanan Wi-Fi tidak didukung oleh alur koneksi '
            'WPA2 LandCam saat ini.',
      );
    }

    final normalizedPassword = password ?? '';

    if (isOpen) {
      if (normalizedPassword.isNotEmpty) {
        return QrPairingResult.failure(
          failure: QrPairingFailure.invalidPassword,
          format: format,
          message:
              'QR menyatakan jaringan terbuka, tetapi juga berisi password.',
        );
      }

      return QrPairingResult.success(
        format: format,
        credentials: CameraWifiCredentials(
          ssid: normalizedSsid,
          password: '',
          securityType: 'NONE',
        ),
      );
    }

    if (password == null || normalizedPassword.isEmpty) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.missingPassword,
        format: format,
        message: 'Password Wi-Fi tidak ditemukan pada QR.',
      );
    }

    // Android's setWpa2Passphrase flow expects an 8-63 character passphrase.
    final passwordLength = normalizedPassword.runes.length;

    if (passwordLength < 8 || passwordLength > 63) {
      return QrPairingResult.failure(
        failure: QrPairingFailure.invalidPassword,
        format: format,
        message:
            'Password WPA2 harus memiliki panjang 8 sampai 63 karakter '
            'untuk alur koneksi LandCam saat ini.',
      );
    }

    return QrPairingResult.success(
      format: format,
      credentials: CameraWifiCredentials(
        ssid: normalizedSsid,
        password: normalizedPassword,
        securityType: 'WPA2',
      ),
    );
  }
}
