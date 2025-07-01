package com.example.spoton

import com.example.spoton.Spot
import android.os.Bundle
import android.annotation.SuppressLint
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.spoton.ui.theme.SpotOnTheme
import com.google.android.gms.location.LocationServices
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.content.pm.PackageManager
import androidx.compose.material3.Card
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.spoton.R
import androidx.compose.ui.res.painterResource
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import androidx.appcompat.content.res.AppCompatResources

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
                var refresh by remember { mutableStateOf(0) }
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
                            Button(onClick = {
                                val signInIntent = googleSignInClient.signInIntent
                                launcher.launch(signInIntent)
                            }) {
                                Text("Zaloguj się przez Google")
                            }
                        } else {
                            Text("Witaj, ${user?.displayName}")
                            Spacer(modifier = Modifier.height(16.dp))
                            OpenStreetMapDialog(refresh = refresh, onRefresh = { refresh++ })
                            Spacer(modifier = Modifier.height(16.dp))
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
fun OpenStreetMapDialog(refresh: Int, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.width(350.dp).height(350.dp)
        ) {
            OpenStreetMapView(
                modifier = Modifier.width(350.dp).height(350.dp),
                refresh = refresh,
                onRefresh = onRefresh
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun OpenStreetMapView(
    modifier: Modifier = Modifier,
    refresh: Int,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    var location by remember { mutableStateOf<Location?>(null) }
    var permissionGranted by remember { mutableStateOf(false) }
    var userSpots by remember { mutableStateOf<List<Spot>>(emptyList()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val db = Firebase.firestore

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationCallback = remember {
        object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val newLocation = result.lastLocation
                if (newLocation != null) {
                    location = newLocation
                    onRefresh()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        permissionGranted = isGranted
    }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    DisposableEffect(permissionGranted) {
        if (permissionGranted) {
            val request = com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 5000L
            ).build()
            fusedLocationClient.requestLocationUpdates(request, locationCallback, null)
        }
        onDispose {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    LaunchedEffect(permissionGranted, refresh) {
        val user = FirebaseAuth.getInstance().currentUser
        if (permissionGranted && user != null) {
            db.collection("users")
                .document(user.uid)
                .collection("spots")
                .get()
                .addOnSuccessListener { result ->
                    userSpots = result.mapNotNull { it.toObject(Spot::class.java) }
                }
        }
    }

    if (location != null) {
        Box(modifier = modifier) {
           AndroidView(
               modifier = Modifier.fillMaxSize(),
               factory = {
                   Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                   val map = MapView(context)
                   map.setMultiTouchControls(true)
                   map
               },
               update = { map ->
                   map.overlays.clear()
                   location?.let {
                       val userPoint = GeoPoint(it.latitude, it.longitude)
                       val marker = Marker(map)
                       marker.position = userPoint
                       marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                       marker.title = "Twoja lokalizacja"
                       map.overlays.add(marker)
                   }
                   userSpots.forEach { spot ->
                       val spotMarker = Marker(map)
                       spotMarker.position = GeoPoint(spot.latitude, spot.longitude)
                       spotMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                       spotMarker.title = spot.name
                       val icon = AppCompatResources.getDrawable(context, R.drawable.ic_baseline_star_24)
                       if (icon != null) {
                           spotMarker.icon = icon
                       }
                       map.overlays.add(spotMarker)
                   }
                   map.invalidate()
               }
           )
            FloatingActionButton(
                onClick = {
                    location?.let {
                        addCurrentLocationToFavorites(it, "Moje ulubione miejsce")
                        scope.launch { snackbarHostState.showSnackbar("Dodano do twoich miejsc") }
                        val user = FirebaseAuth.getInstance().currentUser
                        if (user != null) {
                            db.collection("users")
                                .document(user.uid)
                                .collection("spots")
                                .get()
                                .addOnSuccessListener { result ->
                                    userSpots = result.mapNotNull { it.toObject(Spot::class.java) }
                                    onRefresh()
                                }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Dodaj do ulubionych",
                    tint = Color.White
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    } else if (!permissionGranted) {
        Text("Aby wyświetlić mapę, nadaj uprawnienia do lokalizacji.")
    } else {
        Text("Pobieranie lokalizacji...")
    }
}