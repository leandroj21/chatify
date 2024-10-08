package com.pucmm.assignment.chatify.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.messaging.FirebaseMessaging;
import com.pucmm.assignment.chatify.MainActivity;
import com.pucmm.assignment.chatify.R;
import com.pucmm.assignment.chatify.core.utils.UserStatus;
import com.pucmm.assignment.chatify.core.utils.UserStatusUtils;
import com.pucmm.assignment.chatify.search_people.SearchPeople;
import com.pucmm.assignment.chatify.core.models.ChatModel;
import com.pucmm.assignment.chatify.core.models.GroupChatModel;
import com.pucmm.assignment.chatify.core.models.OneToOneChatModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

public class Home extends AppCompatActivity {
    private EventListener<QuerySnapshot> listener;
    FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String userEmail = Objects.requireNonNull(FirebaseAuth.getInstance().getCurrentUser())
            .getEmail();
    RecentChatsAdapter adapter;
    private Set<String> chatIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Register the FCM token for the current user
        registerFMCToken();

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Delete the title from the toolbar
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        
        FloatingActionButton newChatButton = findViewById(R.id.floatingActionButton);
        newChatButton.setOnClickListener(v -> {
            Intent intent = new Intent(Home.this, SearchPeople.class);
            startActivity(intent);
        });


        final List<ChatModel> chats = new ArrayList<>();

        RecyclerView recyclerView = findViewById(R.id.recyclerViewRecentChats);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        getInitialChats(queryDocumentSnapshots -> {
            for (QueryDocumentSnapshot doc : queryDocumentSnapshots) {
                chats.add(transformDocumentToChat(userEmail, doc));
                chatIds.add(doc.getId());
            }

            adapter = new RecentChatsAdapter(
                    getApplicationContext(),
                    chats
            );

            recyclerView.setAdapter(adapter);
            createRecentChatsListener();
        });

        listener = (value, error) -> {
            if (error != null || value == null) return;

            for (DocumentChange documentChange : value.getDocumentChanges()) {
                if (documentChange.getType() == DocumentChange.Type.MODIFIED) {
                    QueryDocumentSnapshot doc = documentChange.getDocument();

                    int pos = IntStream.range(0, chats.size())
                            .filter(i -> chats.get(i).getId().equals(doc.getId()))
                            .findFirst()
                            .orElse(-1);

                    // New chat
                    if (pos == -1 && !chatIds.contains(doc.getId())) {
                        chatIds.add(doc.getId());
                        chats.add(0, transformDocumentToChat(userEmail, doc));
                        chats.get(0).setNew(true);
                        adapter.notifyDataSetChanged();
                        return;
                    }

                    chats.get(pos).updateLastMessage(doc);
                    chats.get(pos).setNew(true);

                    Collections.sort(chats, (o1, o2) -> o2.getLastMessage().getTimestamp().compareTo(o1.getLastMessage().getTimestamp()));
                    adapter.notifyDataSetChanged();
                } else if (documentChange.getType() == DocumentChange.Type.ADDED) {
                    QueryDocumentSnapshot doc = documentChange.getDocument();
                    if (chatIds.contains(doc.getId())) return;
                    chatIds.add(doc.getId());

                    chats.add(0, transformDocumentToChat(userEmail, doc));
                    chats.get(0).setNew(true);
                    adapter.notifyDataSetChanged();
                }
            }
        };
    }

    private void getInitialChats(@NonNull OnSuccessListener<QuerySnapshot> onSuccessListener) {
        db.collection("conversations")
                .whereArrayContains("members", userEmail)
                .orderBy("lastMessage.timestamp", Query.Direction.DESCENDING)
                .get().addOnSuccessListener(onSuccessListener);
    }

    public static ChatModel transformDocumentToChat(String userEmail, DocumentSnapshot doc) {
        String type = doc.getString("type");
        assert type != null;

        if (type.equalsIgnoreCase(ChatModel.groupIdentifier)) {
            return GroupChatModel.fromDocument(userEmail, doc);
        } else {
            return OneToOneChatModel.fromDocument(userEmail, doc);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_logout) {
            UserStatusUtils.markUserStatus(UserStatus.OFFLINE, task -> {
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener(ignored -> {
                    SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                            getString(R.string.preference_file_key), Context.MODE_PRIVATE
                    );
                    sharedPref.edit().putBoolean(getString(R.string.is_registered_key), false).apply();

                    FirebaseAuth.getInstance().signOut();
                    Intent intent = new Intent(Home.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                });
            });

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (adapter == null) return;
        createRecentChatsListener();
    }
    @Override
    protected void onStart() {
        super.onStart();
        UserStatusUtils.markUserStatus(UserStatus.ONLINE, task -> {});
    }

    void createRecentChatsListener() {
        db.collection("conversations")
                .whereArrayContains("members", userEmail)
                .orderBy("lastMessage.timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener(listener);
    }

    void registerFMCToken() {
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.preference_file_key), Context.MODE_PRIVATE
        );

        if (sharedPref.getBoolean(getString(R.string.is_registered_key), false)) return;

        messaging.getToken().addOnSuccessListener(token -> {
            db.collection("users").limit(1).whereEqualTo("email", userEmail)
                    .get().addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        db.collection("users")
                                .document(queryDocumentSnapshots.getDocuments().get(0).getId())
                                .update("fcmToken", token)
                                .addOnSuccessListener(aVoid -> {
                                    sharedPref.edit().putBoolean(getString(R.string.is_registered_key), true).apply();
                                    Log.i("HomePage", "FCM Token registered successfully");
                                });
                    }
            });
        });
    }
}