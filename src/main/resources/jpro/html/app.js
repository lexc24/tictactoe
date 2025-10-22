
var connectionId;
document.addEventListener('DOMContentLoaded', function() {
  // Elements for username modal
  const usernameModal = document.getElementById('username-modal');
  const usernameInput = document.getElementById('username-input');
  const usernameSubmit = document.getElementById('username-submit');
  let username = '';
  let ws; // Global variable for the WebSocket instance

  usernameSubmit.addEventListener('click', function() {
    username = usernameInput.value.trim();
    if (username !== '') {
      usernameModal.style.display = 'none';
      // After username is entered, initiate the WebSocket connection
      connectWebSocket();
    } else {
      alert('Please enter a username');
    }
  });

  // Listen for the custom gameOver event dispatched from the Java game logic.
  document.addEventListener('gameOverEvent', function(event) {
    // event.detail should contain the game over data from Java
    // For example: { winner: "Player1", loser: "Player2" }
    console.log('Game over event received:', event.detail);
    // Send the gameOver event to your server via WebSocket
    if (ws && ws.readyState === WebSocket.OPEN) {
        console.log('We are ready to send message to websocket');
      ws.send(JSON.stringify({ action: 'gameOVER', data: event.detail }));
    } else {
      console.error('WebSocket is not open.');
    }
  });

  function connectWebSocket() {
    // Replace with your actual WebSocket API Gateway endpoint
    ws = new WebSocket('wss://wqritmruc9.execute-api.us-east-1.amazonaws.com/production');

    // When the connection is opened
    ws.onopen = function() {
      console.log('Connected to WebSocket');
      // Send connection and joinQueue messages
    };

    //This stores the connectionID
    ws.addEventListener("message", function(event) {
      try {
        const message = JSON.parse(event.data);
        console.log('Received message to store connectionId:', message);

        // Assume your backend sends a response like:
        // { "connectionId": "abc123", "message": "Welcome" }
        if (message.connectionId) {
          connectionId = message.connectionId;
          console.log('Stored connectionId:', connectionId);
          ws.send(JSON.stringify({ action: 'updateDB', connectionId:connectionId,username: username  }));

        }
        // Handle other incoming messages as needed.
      } catch (err) {
        console.error('Error parsing message:', err);
      }
    });


    // This messsage hanlder is to handle Queue UI updates
    ws.onmessage = function(messageEvent) {
      try {
        const message = JSON.parse(messageEvent.data);
        console.log("Current message:")

        console.log(message)

        // Check the event type and call the appropriate function
        if (message.action === 'queueUpdate') {
          //console.log("We are in :)))))")
          updateQueueUI(message.data);
        }
        // You can add additional event handlers here if needed.
      } catch (err) {
        console.error('Error parsing message:', err);
      }
    };

    // Handle connection closure
    ws.onclose = function() {
      alert('Disconnected from server. Please check your connection.');
    };

    // Handle errors
    ws.onerror = function(err) {
      console.error('WebSocket error:', err);
    };
  }

  // Function to update the queue display on the sidebar
  function updateQueueUI(queueData) {
    console.log("----We have entered the updateQueue fn in app.js----")
    const queueList = document.getElementById('queue-list');
    const nextUpSpan = document.getElementById('next-up');


    activePlayers = queueData.filter(user => user.status === "active");
    inactivePlayers = queueData.filter(user => user.status === "inactive");

    changeActivePlayersUI(activePlayers)

    //sets active players div manipulation
    setActiveCharacters(queueData);

    // Clear the existing list
    queueList.innerHTML = '';

    inactivePlayers.forEach((player) => {
      const listItem = document.createElement('li');
      let displayText = player.username;
      listItem.textContent = displayText;
      queueList.appendChild(listItem);
    });
  }
function changeActivePlayersUI(data) {
  const player1El = document.getElementById('player1-name');
  const player2El = document.getElementById('player2-name');

  // Helper to extract the username from a label (ignoring the marker)
  const getUsername = el => el.textContent.split(' - ')[0].trim();

  if (data.length === 1) {
    const active = data[0];
    // If the active player's name isn't already displayed, put it in player1
    if (getUsername(player1El) !== active.username && getUsername(player2El) !== active.username) {
      player1El.textContent = `${active.username} - ${active.marker}`;
    }
    // Clear player2 so only one is shown
    player2El.textContent = "";
    return;
  }

  if (data.length === 2) {
    const [active1, active2] = data;
    // If player1 doesn't show one of the active players, update it to active1
    if (![active1.username, active2.username].includes(getUsername(player1El))) {
      player1El.textContent = `${active1.username} - ${active1.marker}`;
    }
    // For player2, if it's not showing an active player, fill it with the other one
    if (![active1.username, active2.username].includes(getUsername(player2El))) {
      // Avoid duplicating what's in player1
      if (getUsername(player1El) === active1.username) {
        player2El.textContent = `${active2.username} - ${active2.marker}`;
      } else {
        player2El.textContent = `${active1.username} - ${active1.marker}`;
      }
    }
  }
}

function setActiveCharacters(queueData) {
  // Look up the current user's info using the connectionId.
  const currentUser = queueData.find(player => player.connectionId === connectionId);
  if (currentUser) {
    enableGameInteraction(currentUser.status === "active");
  } else {
    // Optionally disable interaction if the current user isn't found.
    enableGameInteraction(false);
  }
}

  /**
   * Enables or disables user interaction on the game div.
   */
  function enableGameInteraction(isEnabled) {
      const gameDiv = document.getElementById("game-embed");
      if (!gameDiv) return;
      if (isEnabled) {
          gameDiv.style.pointerEvents = "auto";
          gameDiv.style.opacity = "1";
      } else {
          gameDiv.style.pointerEvents = "none";
          gameDiv.style.opacity = "0.2";  // Gray out the game area.
      }
  }
});




