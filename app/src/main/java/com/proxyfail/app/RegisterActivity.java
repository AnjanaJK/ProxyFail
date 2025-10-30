package com.proxyfail.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.firebase.auth.*;
import com.google.firebase.firestore.*;
import java.util.*;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    private EditText name, email, password;
    private Spinner spinnerRole;
    private Button buttonRegister;
    private ProgressBar progressBar;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private CardView cardInputs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        name = findViewById(R.id.editTextName);
        email = findViewById(R.id.editTextEmail);
        password = findViewById(R.id.editTextPassword);
        spinnerRole = findViewById(R.id.spinnerRole);
        buttonRegister = findViewById(R.id.buttonRegister);
        progressBar = findViewById(R.id.progressBar);
        cardInputs = findViewById(R.id.cardInputs);
        TextView textLogin = findViewById(R.id.textLogin);

        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        setupRoleSpinner();

        // Animate entry
        cardInputs.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(700)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        buttonRegister.setOnClickListener(v -> registerUser());
        if (textLogin != null) {
            textLogin.setOnClickListener(v -> finish());
        }
    }

    private void setupRoleSpinner() {
        String[] roles = {"Student", "Teacher"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                roles
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRole.setAdapter(adapter);
        spinnerRole.setSelection(0);
    }

    private void registerUser() {
        String userName = name.getText().toString().trim();
        String userEmail = email.getText().toString().trim();
        String userPassword = password.getText().toString().trim();
        String selectedRole = spinnerRole.getSelectedItem().toString().toLowerCase();

        if (userName.isEmpty()) {
            name.setError("Name is required");
            return;
        }
        if (userEmail.isEmpty()) {
            email.setError("Email is required");
            return;
        }
        if (userPassword.length() < 6) {
            password.setError("Password must be at least 6 characters");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        buttonRegister.setEnabled(false);

        auth.createUserWithEmailAndPassword(userEmail, userPassword)
                .addOnSuccessListener(authResult -> {
                    FirebaseUser user = authResult.getUser();
                    if (user == null) {
                        showError("Registration failed: User object is null");
                        return;
                    }

                    // Save profile in Firestore
                    Map<String, Object> userProfile = new HashMap<>();
                    userProfile.put("name", userName);
                    userProfile.put("email", userEmail);
                    userProfile.put("role", selectedRole);
                    userProfile.put("createdAt", FieldValue.serverTimestamp());

                    db.collection("users").document(user.getUid())
                            .set(userProfile)
                            .addOnSuccessListener(unused -> {
                                Toast.makeText(this,
                                        "Account created successfully as " + selectedRole,
                                        Toast.LENGTH_LONG).show();

                                // Delay 1s to ensure FirebaseAuth updates state
                                new android.os.Handler().postDelayed(() -> {
                                    navigateToDashboard(selectedRole);
                                }, 1000);
                            })
                            .addOnFailureListener(e -> showError("Profile save failed: " + e.getMessage()));
                })
                .addOnFailureListener(e -> showError("Registration failed: " + e.getMessage()));
    }

    private void navigateToDashboard(String role) {
        progressBar.setVisibility(View.GONE);
        buttonRegister.setEnabled(true);

        Intent intent;
        if (role.equals("teacher")) {
            // Temporarily use StudentDashboardActivity if Teacher one not built yet
            intent = new Intent(this, TeacherDashboardActivity.class);
        } else {
            intent = new Intent(this, AttendanceActivity.class);
        }

        startActivity(intent);
        finish();
    }

    private void showError(String message) {
        progressBar.setVisibility(View.GONE);
        buttonRegister.setEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
