package dev.forge.server.investigator;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class InvestigatorController {

    private final InvestigatorService investigator;

    public InvestigatorController(InvestigatorService investigator) {
        this.investigator = investigator;
    }

    @GetMapping("/investigator/status")
    public InvestigatorService.Status status() {
        return investigator.status();
    }

    @GetMapping("/experiments/{id}/investigation")
    public InvestigatorService.Analysis analysis(@PathVariable UUID id) {
        return investigator.analyze(id);
    }

    public record Ask(String question) {
    }

    @PostMapping("/experiments/{id}/investigation/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestigationRepository.Investigation ask(@PathVariable UUID id, @RequestBody Ask body) {
        return investigator.ask(id, body == null ? null : body.question());
    }

    @GetMapping("/experiments/{id}/investigation/questions")
    public List<InvestigationRepository.Investigation> history(@PathVariable UUID id) {
        return investigator.history(id);
    }
}
