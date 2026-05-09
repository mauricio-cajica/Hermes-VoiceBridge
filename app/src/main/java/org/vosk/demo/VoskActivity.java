// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class VoskActivity extends Activity implements
        RecognitionListener, LanManager.LanListener {

    private static final String TAG = "VoskActivity";

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_MIC = 4;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private static final int PERMISSIONS_REQUEST_WRITE_STORAGE = 2;

    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;

    private View recognitionLayout;
    private View customizationLayout;
    private View lanLayout;
    private TextView fontSizeLabel;
    
    private FloatingActionButton recordBtn;
    private FloatingActionButton saveBtn;
    private ImageButton navRecognition, navCustomization, navLan;
    
    private LanManager lanManager;
    private List<NsdServiceInfo> discoveredRooms = new ArrayList<>();
    private ArrayAdapter<String> roomsAdapter;
    private boolean isHosting = false;

    private StringBuilder transcript = new StringBuilder();

    @Override
    public void onCreate(Bundle state) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        recognitionLayout = findViewById(R.id.recognition_layout);
        customizationLayout = findViewById(R.id.customization_layout);
        lanLayout = findViewById(R.id.lan_layout);
        fontSizeLabel = findViewById(R.id.font_size_label);
        recordBtn = findViewById(R.id.record_btn);
        saveBtn = findViewById(R.id.save_btn);
        
        navRecognition = findViewById(R.id.nav_recognition);
        navCustomization = findViewById(R.id.nav_customization);
        navLan = findViewById(R.id.nav_lan);

        setUiState(STATE_START);

        recordBtn.setOnClickListener(view -> recognizeMicrophone());
        saveBtn.setOnClickListener(view -> checkStoragePermissionAndSave());

        setupNavigation();
        setupCustomization();
        setupLan();

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    private void checkStoragePermissionAndSave() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_WRITE_STORAGE);
                return;
            }
        }
        saveTranscript();
    }

    private void saveTranscript() {
        if (transcript.length() == 0 && (resultView.getText().length() == 0 || resultView.getText().equals(getString(R.string.ready)))) {
            Toast.makeText(this, R.string.no_text_to_save, Toast.LENGTH_SHORT).show();
            return;
        }

        String contentToSave = transcript.toString();
        // Append current result view text if it has something not in transcript
        String currentText = resultView.getText().toString();
        if (!contentToSave.contains(currentText) && !currentText.equals(getString(R.string.ready)) && !currentText.equals(getString(R.string.say_something))) {
            contentToSave += currentText + "\n";
        }

        String fileName = "Transcript_" + System.currentTimeMillis() + ".txt";
        boolean success = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os != null) {
                        os.write(contentToSave.getBytes());
                        success = true;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error saving file", e);
                }
            }
        } else {
            java.io.File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            java.io.File file = new java.io.File(path, fileName);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                fos.write(contentToSave.getBytes());
                success = true;
            } catch (IOException e) {
                Log.e(TAG, "Error saving file", e);
            }
        }

        if (success) {
            Toast.makeText(this, getString(R.string.text_saved, fileName), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, getString(R.string.save_error, "Unknown"), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupNavigation() {
        View.OnClickListener navListener = v -> {
            recognitionLayout.setVisibility(View.GONE);
            customizationLayout.setVisibility(View.GONE);
            lanLayout.setVisibility(View.GONE);
            
            navRecognition.setImageTintList(ColorStateList.valueOf(Color.parseColor("#757575")));
            navCustomization.setImageTintList(ColorStateList.valueOf(Color.parseColor("#757575")));
            navLan.setImageTintList(ColorStateList.valueOf(Color.parseColor("#757575")));

            int id = v.getId();
            if (id == R.id.nav_recognition) {
                recognitionLayout.setVisibility(View.VISIBLE);
                navRecognition.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.purple_500)));
            } else if (id == R.id.nav_customization) {
                customizationLayout.setVisibility(View.VISIBLE);
                navCustomization.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.purple_500)));
            } else if (id == R.id.nav_lan) {
                lanLayout.setVisibility(View.VISIBLE);
                navLan.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.purple_500)));
            }
        };

        navRecognition.setOnClickListener(navListener);
        navCustomization.setOnClickListener(navListener);
        navLan.setOnClickListener(navListener);
    }

    private void setupCustomization() {
        // Font Size
        SeekBar fontSizeSeekBar = findViewById(R.id.font_size_seekbar);
        int initialProgress = (int) (resultView.getTextSize() / getResources().getDisplayMetrics().scaledDensity);
        fontSizeSeekBar.setProgress(initialProgress);
        fontSizeLabel.setText(getString(R.string.font_size_label, initialProgress));

        fontSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int size = progress;
                if (size < 10) size = 10; // Minimum font size
                resultView.setTextSize(size);
                fontSizeLabel.setText(getString(R.string.font_size_label, size));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Font Family
        Spinner fontFamilySpinner = findViewById(R.id.font_family_spinner);
        String[] fonts = {"Default", "Sans Serif", "Serif", "Monospace"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, fonts);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fontFamilySpinner.setAdapter(adapter);

        fontFamilySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0: resultView.setTypeface(Typeface.DEFAULT); break;
                    case 1: resultView.setTypeface(Typeface.SANS_SERIF); break;
                    case 2: resultView.setTypeface(Typeface.SERIF); break;
                    case 3: resultView.setTypeface(Typeface.MONOSPACE); break;
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupLan() {
        lanManager = new LanManager(this, this);
        
        EditText roomNameEdit = findViewById(R.id.room_name_edit);
        Button createRoomBtn = findViewById(R.id.create_room_btn);
        ListView roomsList = findViewById(R.id.rooms_list);
        
        roomsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        roomsList.setAdapter(roomsAdapter);
        
        createRoomBtn.setOnClickListener(v -> {
            if (!isHosting) {
                String name = roomNameEdit.getText().toString().trim();
                if (!name.isEmpty()) {
                    lanManager.createRoom(name);
                    createRoomBtn.setText(R.string.stop_room);
                    isHosting = true;
                }
            } else {
                lanManager.stop();
                createRoomBtn.setText(R.string.create_room);
                isHosting = false;
            }
        });
        
        roomsList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < discoveredRooms.size()) {
                lanManager.connectToRoom(discoveredRooms.get(position));
            }
        });
        
        lanManager.discoverRooms();
    }

    @Override
    public void onRoomDiscovered(NsdServiceInfo serviceInfo) {
        runOnUiThread(() -> {
            discoveredRooms.add(serviceInfo);
            roomsAdapter.add(serviceInfo.getServiceName());
            roomsAdapter.notifyDataSetChanged();
        });
    }

    @Override
    public void onSubtitleReceived(String text) {
        runOnUiThread(() -> {
            resultView.setText(text);
            final ScrollView scrollView = (ScrollView) resultView.getParent();
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onConnectionStatusChanged(String status) {
        runOnUiThread(() -> ((TextView) findViewById(R.id.connection_status)).setText("Status: " + status));
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> ((TextView) findViewById(R.id.connection_status)).setText("Error: " + error));
    }

    private void initModel() {
        StorageService.unpack(this, "model-es", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initModel();
            } else {
                finish();
            }
        } else if (requestCode == PERMISSIONS_REQUEST_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveTranscript();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
        
        if (lanManager != null) {
            lanManager.stop();
        }
    }

    private void actualizarTexto(String hypothesis, String key) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(hypothesis);
            if (json.has(key)) {
                String textoLimpio = json.getString(key);
                if (!textoLimpio.trim().isEmpty()) {
                    String display;
                    if (key.equals("partial")) {
                        display = transcript.toString() + textoLimpio;
                    } else { // "text"
                        transcript.append(textoLimpio).append("\n");
                        display = transcript.toString();
                    }
                    
                    resultView.setText(display);
                    
                    // Auto-scroll to bottom
                    final ScrollView scrollView = (ScrollView) resultView.getParent();
                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

                    if (isHosting) {
                        lanManager.sendSubtitle(display);
                    }
                }
            }
        } catch (org.json.JSONException e) {
            Log.e(TAG, "JSON parsing error", e);
        }
    }

    @Override
    public void onResult(String hypothesis) {
        actualizarTexto(hypothesis, "text");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        actualizarTexto(hypothesis, "text");
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        actualizarTexto(hypothesis, "partial");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                recordBtn.setEnabled(false);
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                recordBtn.setImageResource(R.drawable.ic_mic);
                recordBtn.setEnabled(true);
                break;
            case STATE_DONE:
                recordBtn.setImageResource(R.drawable.ic_mic);
                recordBtn.setEnabled(true);
                break;
            case STATE_MIC:
                recordBtn.setImageResource(R.drawable.ic_stop);
                // Don't clear resultView here if we want persistent text
                // resultView.setText(getString(R.string.say_something));
                recordBtn.setEnabled(true);
                break;
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        recordBtn.setImageResource(R.drawable.ic_mic);
        recordBtn.setEnabled(false);
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }
}
