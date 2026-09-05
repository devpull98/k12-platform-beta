<?php

use App\Http\Controllers\MetricsController;
use Illuminate\Support\Facades\Route;

Route::get('/', fn () => response()->json(['service' => 'laravel-metrics', 'ok' => true]));
Route::get('/metrics', [MetricsController::class, 'metrics']);
Route::get('/work', [MetricsController::class, 'work']);
Route::get('/db', [MetricsController::class, 'db']);
