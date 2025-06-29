package com.example.spoton

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.spoton.ui.theme.SpotOnTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseUser
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import android.Manifest
import android.content.Context
import android.location.Location
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import com.google.android.gms.location.LocationServices
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import kotlinx.coroutines.tasks.await
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        auth = FirebaseAuth.getInstance()

        setContent {
            SpotOnTheme {
                var user by remember { mutableStateOf(auth.currentUser) }
                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    try {
                        val account = task.getResult(Exception::class.java)
                        firebaseAuthWithGoogle(account, onResult = { u -> user = u })
                    } catch (e: Exception) {
                        Log.w(TAG, "Google sign in failed", e)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (user == null) {
                            GoogleSignInButton(onClick = {
                                val signInIntent = googleSignInClient.signInIntent
                                launcher.launch(signInIntent)
                            })
                        } else {
                            Text("Witaj, ${user?.displayName}")
                            Spacer(modifier = Modifier.height(16.dp))
                            OpenStreetMapDialog()
                            Button(onClick = {
                                auth.signOut()
                                googleSignInClient.signOut()
                                user = null
                            }) {
                                Text("Wyloguj się")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun firebaseAuthWithGoogle(
        acct: GoogleSignInAccount?,
        onResult: (FirebaseUser?) -> Unit
    ) {
        val credential = GoogleAuthProvider.getCredential(acct?.idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    onResult(auth.currentUser)
                } else {
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    onResult(null)
                }
            }
    }
}

@Composable
fun OpenStreetMapDialog() {
    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(350.dp)
                .height(350.dp)
        ) {
            OpenStreetMapView(
                modifier = Modifier
                    .fillMaxSize()
            )
        }
    }
}

@Composable
fun OpenStreetMapView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }
    var permissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        permissionGranted = isGranted
    }

    // Pobierz lokalizację użytkownika
    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            @SuppressLint("MissingPermission")
            suspend fun getLocation(): Location? {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                return fusedLocationClient.lastLocation.await()
            }
            location = getLocation()
        }
    }

    if (location != null) {
        AndroidView(
            modifier = modifier,
            factory = {
            Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            val map = MapView(context)
            map.setMultiTouchControls(true)
            val userPoint = GeoPoint(location!!.latitude, location!!.longitude)
            map.controller.setZoom(15.0)
            map.controller.setCenter(userPoint)

            val marker = Marker(map)
            marker.position = userPoint
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.title = "Twoja lokalizacja"
            map.overlays.add(marker)
            map
        })
    } else if (!permissionGranted) {
        Text("Aby wyświetlić mapę, nadaj uprawnienia do lokalizacji.")
    } else {
        Text("Pobieranie lokalizacji...")
    }
}