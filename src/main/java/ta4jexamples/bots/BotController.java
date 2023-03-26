package ta4jexamples.bots;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BotController{

    @GetMapping("/v1/hello")
    public String sayHello(){
        return "hello";
    }
;
}