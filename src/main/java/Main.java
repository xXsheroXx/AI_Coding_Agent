import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Main {
    public static void main(String[] args) {
        if (args.length < 2 || !"-p".equals(args[0])) {
            System.err.println("Usage: program -p <prompt>");
            System.exit(1);
        }

        String prompt = args[1];

        String apiKey = System.getenv("OPENROUTER_API_KEY");
        String baseUrl = System.getenv("OPENROUTER_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "https://openrouter.ai/api/v1";
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENROUTER_API_KEY is not set");
        }

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        var paramBuilder =
                ChatCompletionCreateParams.builder()
                        .model("anthropic/claude-haiku-4.5")
                        .addUserMessage(prompt)
                        .tools(getAvailableTools());

        ChatCompletion response = client.chat().completions().create(paramBuilder.build());
        if (response.choices().isEmpty()) {
            throw new RuntimeException("no choices in response");
        }

        // You can use print statements as follows for debugging, they'll be visible when running tests.
        System.err.println("Logs from your program will appear here!");

        ChatCompletion.Choice choice = response.choices().get(0);
        Optional<List<ChatCompletionMessageToolCall>> toolCalls = choice.message().toolCalls();
        if (toolCalls.isEmpty()) {
            System.out.print(choice.message().content().orElse(""));
        } else {
            parseTools(toolCalls.get());
        }

        runLoop(client, paramBuilder);

           // TODO: Uncomment the line below to pass the first stage
//         System.out.print(response.choices().get(0).message().content().orElse(""));
    }

    static void parseTools(List<ChatCompletionMessageToolCall> toolCalls) {
        ChatCompletionMessageToolCall toolCall = toolCalls.getFirst();
        String functionName = toolCall.function().name();

        if (functionName.equals("Read")) {
            execute(toolCall.function().arguments());
        } else {
            throw new RuntimeException("Unknown function: " + functionName);
        }
    }

    private static String handleToolExecution(ChatCompletionMessageToolCall toolCall) {
        String functionName = toolCall.function().name();

        if (functionName.equals("Read")) {
            return execute(toolCall.function().arguments());
        }
        return "Error: Unknown function: " + functionName;
    }

    static String execute(String arguments) {
        JsonNode argsNode;
        ObjectMapper mapper = new ObjectMapper();

        try {
            argsNode = mapper.readTree(arguments);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse function arguments: ", e);
        }

        String filePath = argsNode.get("file_path").asText();
        File objectFile = new File(filePath);
        try {
            return Files.readString(objectFile.toPath());
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    private static List<ChatCompletionTool> getAvailableTools() {
        var readParameters =
                FunctionParameters.builder()
                        .putAdditionalProperty("type", JsonValue.from("object"))
                        .putAdditionalProperty("properties",
                                JsonValue.from(
                                        Map.of(
                                                "file_path",
                                                Map.of(
                                                        "type", "string",
                                                        "description", "The path to the file to read, relative to the current directory."))))
                        .build();

        var readTool =
                ChatCompletionTool.builder()
                        .type(JsonValue.from("function"))
                        .function(
                                FunctionDefinition.builder()
                                        .name("Read")
                                        .description("Read and return the contents of a file")
                                        .parameters(readParameters)
                                        .build())
                        .build();

        return List.of(readTool);
    }

    private static void runLoop(OpenAIClient client, ChatCompletionCreateParams.Builder parambuilder) {
        while (true) {
            ChatCompletion response = client.chat().completions().create(parambuilder.build());

            if (response.choices().isEmpty()) {
                throw new RuntimeException("no choices in response");
            }
            var choice = response.choices().getFirst();
            var assistantMessage = choice.message();
            parambuilder.addMessage(assistantMessage);
            var toolsOpt = assistantMessage.toolCalls();

            if (toolsOpt.isPresent() && !toolsOpt.get().isEmpty()) {
                var toolCalls = toolsOpt.get();

                for (var toolCall : toolCalls) {
                    var result = handleToolExecution(toolCall);
                    var toolResponseMessage =
                            ChatCompletionMessageParam.ofTool(
                                    ChatCompletionToolMessageParam.builder()
                                            .toolCallId(toolCall.id())
                                            .content(result)
                                            .build());
                    parambuilder.addMessage(toolResponseMessage);
                }
            } else {
                System.out.print(assistantMessage.content().orElse(""));
                break;
            }
        }
    }
}
