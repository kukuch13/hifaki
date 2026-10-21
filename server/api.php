<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function respond(int $code, array $data): never {
    http_response_code($code);
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_THROW_ON_ERROR);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') respond(405, ['error' => 'POST only']);
if (empty($_SERVER['HTTPS']) || $_SERVER['HTTPS'] === 'off') respond(403, ['error' => 'HTTPS required']);
if ((int) ($_SERVER['CONTENT_LENGTH'] ?? 0) > 4096) respond(413, ['error' => 'Payload too large']);

// Deploy api.php under public_html/hifaki/api.php and config.php under ~/private/.
$configFile = dirname(__DIR__, 2) . '/private/config.php';
if (!is_file($configFile)) respond(503, ['error' => 'Server not configured']);
$config = require $configFile;
if (strlen((string) ($config['api_key'] ?? '')) < 32 ||
    !hash_equals((string) $config['api_key'], (string) ($_SERVER['HTTP_X_HIFAKI_KEY'] ?? ''))) {
    respond(401, ['error' => 'Unauthorized']);
}

try {
    $input = json_decode(file_get_contents('php://input', false, null, 0, 4097), true, 32, JSON_THROW_ON_ERROR);
    if (!is_array($input)) respond(400, ['error' => 'Invalid JSON']);
    $id = (string) ($input['id'] ?? '');
    $name = trim((string) ($input['name'] ?? ''));
    $time = $input['measured_at'] ?? null;
    if (!preg_match('/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/iD', $id) ||
        !preg_match('/\A[^\p{C}]{2,80}\z/u', $name) ||
        !is_int($time) || $time < 1577836800000 || $time > (time() + 86400) * 1000) {
        respond(422, ['error' => 'Invalid record fields']);
    }
    $skin = validNumber($input['skin_c'] ?? null, -20, 60);
    $bpm = validNumber($input['bpm'] ?? null, 30, 220);
    $altitude = validNumber($input['altitude_m'] ?? null, -500, 9000);
    $core = validNumber($input['core_c'] ?? null, 0, 45);
    $latitude = validNumber($input['latitude'] ?? null, -90, 90);
    $longitude = validNumber($input['longitude'] ?? null, -180, 180);
    $gpsAccuracy = validNumber($input['gps_accuracy_m'] ?? null, 0, 500);
    $gpsAt = $input['gps_at'] ?? null;
    if (($latitude === null) !== ($longitude === null) ||
        ($latitude !== null && (!is_int($gpsAt) || $gpsAt < 1577836800000 ||
            $gpsAt > $time + 60000 || $gpsAccuracy === null)) ||
        ($latitude === null && ($gpsAt !== null || $gpsAccuracy !== null))) {
        throw new InvalidArgumentException();
    }
    $danger = ($input['danger'] ?? false) === true;
    $source = ($skin !== null || $bpm !== null || $altitude !== null) ? 'HIFAKI-C3' : 'Manual';
    $assessment = $danger ? 'DARURAT • gejala berbahaya' :
        ($core === null ? 'Skrining • belum ada suhu inti' :
        ($core < 28 ? 'DARURAT • hipotermia berat' :
        ($core < 32 ? 'DARURAT • hipotermia sedang' :
        ($core < 35 ? 'DARURAT • hipotermia ringan' : 'Suhu inti ≥ 35°C • tetap nilai gejala'))));
    $db = new PDO('mysql:host=' . $config['db_host'] . ';dbname=' . $config['db_name'] . ';charset=utf8mb4',
        $config['db_user'], $config['db_pass'], [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
    $statement = $db->prepare('INSERT INTO hifaki_records
        (id, measured_at, patient_name, skin_c, bpm, altitude_m, latitude, longitude,
         gps_accuracy_m, gps_at, core_c, danger, assessment, source)
        VALUES (:id, :time, :name, :skin, :bpm, :altitude, :latitude, :longitude,
                :gps_accuracy, :gps_at, :core, :danger, :assessment, :source)
        ON DUPLICATE KEY UPDATE id=id');
    $statement->execute([
        ':id' => $id, ':time' => $time, ':name' => $name,
        ':skin' => $skin, ':bpm' => $bpm, ':altitude' => $altitude,
        ':latitude' => $latitude, ':longitude' => $longitude,
        ':gps_accuracy' => $gpsAccuracy, ':gps_at' => $gpsAt,
        ':core' => $core, ':danger' => (int) $danger,
        ':assessment' => $assessment, ':source' => $source,
    ]);
    respond(200, ['saved' => true, 'id' => $id]);
} catch (InvalidArgumentException | JsonException $e) {
    respond(422, ['error' => 'Invalid measurement']);
} catch (Throwable $e) {
    error_log('HIFAKI upload failed: ' . get_class($e));
    respond(500, ['error' => 'Server error']);
}

function validNumber(mixed $value, float $minimum, float $maximum): ?float {
    if ($value === null) return null;
    if (!is_int($value) && !is_float($value)) throw new InvalidArgumentException();
    if (!is_finite((float) $value) || $value < $minimum || $value > $maximum) throw new InvalidArgumentException();
    return (float) $value;
}
