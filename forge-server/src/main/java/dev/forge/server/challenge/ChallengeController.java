package dev.forge.server.challenge;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChallengeController {

    private final ChallengeService challenges;

    public ChallengeController(ChallengeService challenges) {
        this.challenges = challenges;
    }

    @GetMapping("/challenges")
    public List<ChallengeService.Summary> list() {
        return challenges.list();
    }

    @GetMapping("/challenges/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return challenges.get(id);
    }

    public record Start(String hypothesis) {
    }

    @PostMapping("/challenges/{id}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public ChallengeService.AttemptView start(@PathVariable String id, @RequestBody Start body) {
        return challenges.start(id, body == null ? null : body.hypothesis());
    }

    @GetMapping("/challenge-attempts/{id}")
    public ChallengeService.AttemptView attempt(@PathVariable UUID id) {
        return challenges.attempt(id);
    }

    @PostMapping("/challenge-attempts/{id}/hints")
    public ChallengeService.AttemptView hint(@PathVariable UUID id) {
        return challenges.revealHint(id);
    }

    @PutMapping("/challenge-attempts/{id}/answers")
    public ChallengeService.AttemptView answers(@PathVariable UUID id, @RequestBody Map<String, Object> answers) {
        return challenges.saveAnswers(id, answers);
    }
}
