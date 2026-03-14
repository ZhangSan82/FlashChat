package com.flashchat.chatservice.controller;


import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/FlashChat/v1/room")
@CrossOrigin(origins = "*")
@AllArgsConstructor
public class RoomController {
}
