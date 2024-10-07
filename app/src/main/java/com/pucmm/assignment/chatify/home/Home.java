package com.pucmm.assignment.chatify.home;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
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
import java.util.List;
import java.util.Objects;

public class Home extends AppCompatActivity {
    private EventListener<QuerySnapshot> listener;
    FirebaseFirestore db = FirebaseFirestore.getInstance();
    private String userEmail = Objects.requireNonNull(FirebaseAuth.getInstance().getCurrentUser())
            .getEmail();

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
        
        FloatingActionButton newChatButton = findViewById(R.id.floatingActionButton);
        newChatButton.setOnClickListener(v -> {
            Intent intent = new Intent(Home.this, SearchPeople.class);
            startActivity(intent);
        });


        final List<ChatModel> chats = new ArrayList<>();

        RecyclerView recyclerView = findViewById(R.id.recyclerViewRecentChats);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        RecentChatsAdapter adapter = new RecentChatsAdapter(
                getApplicationContext(),
                chats
        );
        recyclerView.setAdapter(adapter);

        listener = (value, error) -> {
            if (error != null || value == null) return;

            chats.clear();

            value.getDocuments().stream()
                    .map(doc -> transformDocumentToChat(userEmail, doc))
                    .forEach(chats::add);

            adapter.notifyDataSetChanged();
        };

        createRecentChatsListener();
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
                FirebaseAuth.getInstance().signOut();
                Intent intent = new Intent(Home.this, MainActivity.class);
                startActivity(intent);
                finish();
            });

            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    FirebaseAuth.getInstance().signOut();
                    Intent intent = new Intent(Home.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                }
            });
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token -> {
            db.collection("users").limit(1).whereEqualTo("email", userEmail)
                    .get().addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        db.collection("users")
                                .document(queryDocumentSnapshots.getDocuments().get(0).getId())
                                .update("fcmToken", token)
                                .addOnSuccessListener(aVoid -> {
                                    Log.i("HomePage", "FCM Token registered successfully");
                                });
                    }
            });
        });
    }
}